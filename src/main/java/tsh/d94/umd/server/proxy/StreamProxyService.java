package tsh.d94.umd.server.proxy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.ProxyHeaderPolicy;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.server.LocalAliveChecker;
import tsh.d94.umd.server.PendingReplyRegistry;
import tsh.d94.umd.server.ServerStreamMetrics;
import tsh.d94.umd.server.StreamBadReplyException;
import tsh.d94.umd.server.StreamCall;
import tsh.d94.umd.server.StreamRequestGateway;
import tsh.d94.umd.server.StreamShutdownException;

/**
 * [server] UI HTTP 요청 → Stream 요청 → (local 응답) → UI HTTP 응답.
 *
 * <pre>
 * 본문 초과             → 413 PAYLOAD_TOO_LARGE   (Redis 미적재)
 * 대기 요청 상한        → 503 OVERLOADED          (Redis 미적재)
 * local heartbeat 없음  → 503 LOCAL_UNAVAILABLE   (Redis 미적재, 즉시)
 * 발행 → 응답           → local 응답 그대로
 *        시간 초과      → 504 TIMEOUT
 *        Redis 장애     → 503 REDIS_UNAVAILABLE
 *        종료 중        → 503 SHUTTING_DOWN
 *        응답 형식 오류 → 502 BAD_REPLY
 * </pre>
 * JWT 확인은 보안 필터가 먼저 수행한다 (실패 시 이 서비스까지 오지 않음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class StreamProxyService {

	private final StreamRequestGateway gateway;
	private final PendingReplyRegistry registry;
	private final LocalAliveChecker localAliveChecker;
	private final UmdProperties properties;
	private final ServerStreamMetrics metrics;

	public CompletableFuture<ProxyResponse> proxy(ProxyRequest request) {
		Timer.Sample sample = metrics.startProxy();

		UmdErrorCode rejection = precheck(request);
		if (rejection != null) {
			metrics.rejected(rejection);
			return CompletableFuture.completedFuture(record(sample, request, ProxyResponse.error(rejection, null)));
		}

		Map<String, List<String>> headers = ProxyHeaderPolicy.withForwarded(
			ProxyHeaderPolicy.forRequest(request.headers()),
			request.remoteAddr(), request.scheme(), request.host(), null);
		StreamCall call = gateway.request(request.method(), request.path(), request.query(), headers, request.body());

		return call.reply().handle((reply, error) -> {
			ProxyResponse response = error == null
				? toResponse(call.corrId(), reply)
				: fromError(call.corrId(), request, unwrap(error));
			return record(sample, request, response);
		});
	}

	private UmdErrorCode precheck(ProxyRequest request) {
		if (request.bodyTooLarge()) {
			return UmdErrorCode.PAYLOAD_TOO_LARGE;
		}
		if (registry.size() >= properties.getServer().getMaxPending()) {
			log.warn("대기 요청 상한 초과. pending={} max={}", registry.size(), properties.getServer().getMaxPending());
			return UmdErrorCode.OVERLOADED;
		}
		if (properties.getServer().isRequireLocalAlive()) {
			try {
				if (!localAliveChecker.isAlive()) {
					return UmdErrorCode.LOCAL_UNAVAILABLE;
				}
			} catch (DataAccessException e) {
				log.warn("Local 생존 확인 실패 (Redis). cause={}", e.getMessage());
				return UmdErrorCode.REDIS_UNAVAILABLE;
			}
		}
		return null;
	}

	private ProxyResponse toResponse(String corrId, StreamHttpReply reply) {
		return ProxyResponse.passthrough(reply.getStatus(), reply.getHeaders(), reply.getBody(), corrId);
	}

	private ProxyResponse fromError(String corrId, ProxyRequest request, Throwable error) {
		UmdErrorCode code = classify(error);
		switch (code) {
			case TIMEOUT -> log.warn("Local 응답 시간 초과. corrId={} {} {}", corrId, request.method(), request.path());
			case REDIS_UNAVAILABLE -> log.warn("Redis 장애. corrId={} cause={}", corrId, error.getMessage());
			case SHUTTING_DOWN -> log.info("종료 중 대기 중단. corrId={}", corrId);
			case BAD_REPLY -> log.error("잘못된 응답. corrId={} cause={}", corrId, error.getMessage());
			default -> log.error("프록시 처리 실패. corrId={} {} {}", corrId, request.method(), request.path(), error);
		}
		return ProxyResponse.error(code, corrId);
	}

	static UmdErrorCode classify(Throwable error) {
		if (error instanceof TimeoutException) {
			return UmdErrorCode.TIMEOUT;
		}
		if (error instanceof StreamShutdownException) {
			return UmdErrorCode.SHUTTING_DOWN;
		}
		if (error instanceof StreamBadReplyException) {
			return UmdErrorCode.BAD_REPLY;
		}
		if (error instanceof DataAccessException) {
			return UmdErrorCode.REDIS_UNAVAILABLE;
		}
		return UmdErrorCode.INTERNAL;
	}

	private ProxyResponse record(Timer.Sample sample, ProxyRequest request, ProxyResponse response) {
		metrics.recordProxy(sample, request.method(), response.status(),
			response.isUmdError() ? response.error().name() : "OK");
		return response;
	}

	private static Throwable unwrap(Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}
}
