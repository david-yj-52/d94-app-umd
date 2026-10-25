package tsh.d94.umd.local;

import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;

/**
 * [local] 지표 (/actuator/prometheus).
 * <ul>
 *   <li>umd_local_dispatch_seconds{method, route, status, outcome}: 로컬 백엔드 호출 시간.
 *       route 는 설정의 route id(없으면 none), status 는 2xx 등 클래스(응답 없음은 none)</li>
 *   <li>umd_local_inflight: 처리 중인 요청 수</li>
 *   <li>umd_local_expired_total: deadline 경과로 폐기한 요청</li>
 *   <li>umd_local_reclaimed_total: Pending 회수로 다시 처리한 요청</li>
 *   <li>umd_local_invalid_total / umd_local_read_errors_total</li>
 * </ul>
 */
@Component
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class LocalMetrics {

	private static final Set<String> KNOWN_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

	private final MeterRegistry meterRegistry;
	private final Counter expired;
	private final Counter reclaimed;
	private final Counter invalid;
	private final Counter readErrors;

	public LocalMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		this.expired = Counter.builder("umd.local.expired").description("deadline 경과로 폐기한 요청")
			.register(meterRegistry);
		this.reclaimed = Counter.builder("umd.local.reclaimed").description("Pending 회수로 다시 처리한 요청")
			.register(meterRegistry);
		this.invalid = Counter.builder("umd.local.invalid").description("형식 오류로 폐기한 요청")
			.register(meterRegistry);
		this.readErrors = Counter.builder("umd.local.read.errors").description("요청 Stream 읽기 실패")
			.register(meterRegistry);
	}

	void registerInflight(IntSupplier inflight) {
		Gauge.builder("umd.local.inflight", inflight, IntSupplier::getAsInt)
			.description("처리 중인 요청 수")
			.register(meterRegistry);
	}

	public Timer.Sample startDispatch() {
		return Timer.start(meterRegistry);
	}

	public void recordDispatch(Timer.Sample sample, String method, String route, int status, String outcome) {
		String upper = method == null ? "" : method.toUpperCase(Locale.ROOT);
		sample.stop(Timer.builder("umd.local.dispatch")
			.description("로컬 백엔드 호출 소요 시간")
			.tag("method", KNOWN_METHODS.contains(upper) ? upper : "OTHER")
			.tag("route", route)
			.tag("status", status == 0 ? "none" : (status / 100) + "xx")
			.tag("outcome", outcome)
			.register(meterRegistry));
	}

	public void expired() {
		expired.increment();
	}

	public void reclaimed(int count) {
		reclaimed.increment(count);
	}

	public void invalidRequest() {
		invalid.increment();
	}

	public void readError() {
		readErrors.increment();
	}
}
