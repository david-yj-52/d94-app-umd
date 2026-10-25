package tsh.d94.umd.server;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.error.UmdErrorCode;

/**
 * [server] 프록시 지표 (/actuator/prometheus).
 * <ul>
 *   <li>umd_proxy_request_seconds{method, status, outcome}: UI 요청 ~ 응답 시간. status 는 2xx 등 클래스,
 *       outcome 은 OK(local 응답 그대로 전달) 또는 UmdErrorCode 이름. 경로는 값 종류가 무한해 태그로 쓰지 않는다.</li>
 *   <li>umd_proxy_rejected_total{reason}: Redis 에 싣기 전에 거절한 요청 (413 / 503)</li>
 *   <li>umd_stream_pending: 응답 대기 중인 요청 수</li>
 *   <li>umd_stream_reply_late_total / _invalid_total / umd_stream_read_errors_total</li>
 * </ul>
 */
@Component
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class ServerStreamMetrics {

	private static final Set<String> KNOWN_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

	private final MeterRegistry meterRegistry;
	private final Counter lateReplies;
	private final Counter invalidReplies;
	private final Counter readErrors;

	public ServerStreamMetrics(MeterRegistry meterRegistry, PendingReplyRegistry pendingReplyRegistry) {
		this.meterRegistry = meterRegistry;
		Gauge.builder("umd.stream.pending", pendingReplyRegistry, PendingReplyRegistry::size)
			.description("응답 대기 중인 요청 수")
			.register(meterRegistry);
		this.lateReplies = Counter.builder("umd.stream.reply.late")
			.description("대기 중인 요청이 없는 응답 (타임아웃 후 도착 등)")
			.register(meterRegistry);
		this.invalidReplies = Counter.builder("umd.stream.reply.invalid")
			.description("형식 오류로 버린 응답")
			.register(meterRegistry);
		this.readErrors = Counter.builder("umd.stream.read.errors")
			.description("응답 Stream 읽기 실패")
			.register(meterRegistry);
	}

	public Timer.Sample startProxy() {
		return Timer.start(meterRegistry);
	}

	public void recordProxy(Timer.Sample sample, String method, int status, String outcome) {
		sample.stop(Timer.builder("umd.proxy.request")
			.description("UI 요청 ~ 응답 소요 시간")
			.tag("method", normalizeMethod(method))
			.tag("status", (status / 100) + "xx")
			.tag("outcome", outcome)
			.register(meterRegistry));
	}

	public void rejected(UmdErrorCode reason) {
		Counter.builder("umd.proxy.rejected")
			.description("Redis 에 싣기 전에 거절한 요청")
			.tag("reason", reason.name())
			.register(meterRegistry)
			.increment();
	}

	public void lateReply() {
		lateReplies.increment();
	}

	public void invalidReply() {
		invalidReplies.increment();
	}

	public void readError() {
		readErrors.increment();
	}

	static String normalizeMethod(String method) {
		String upper = method == null ? "" : method.toUpperCase(Locale.ROOT);
		return KNOWN_METHODS.contains(upper) ? upper : "OTHER";
	}
}
