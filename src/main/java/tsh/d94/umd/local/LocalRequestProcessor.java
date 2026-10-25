package tsh.d94.umd.local;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * [local] 요청 엔트리 1건 처리.
 *
 * <pre>
 * 형식 오류      → XACK + XDEL (replyTo 를 모를 수 있어 응답 불가)
 * deadline 경과  → XACK + XDEL (응답 없음 — server 는 이미 504)
 * route 없음     → 404 UMD_NO_ROUTE 응답
 * 로컬 호출      → 응답 그대로 / 502 / (시간 초과 시 응답 없음)
 * 응답 XADD      → XACK + XDEL (토큰·본문이 담긴 요청 엔트리를 Redis 에 남기지 않음)
 * </pre>
 * 응답 XADD 가 실패하면(Redis 장애) ACK 하지 않는다 → Pending 에 남아 회수 대상이 된다.
 */
@Slf4j
@Component
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class LocalRequestProcessor {

	private final LocalRouteResolver routeResolver;
	private final LocalHttpDispatcher dispatcher;
	private final ReplyPublisher replyPublisher;
	private final StringRedisTemplate redisTemplate;
	private final LocalMetrics metrics;
	private final Clock clock;

	@Autowired
	public LocalRequestProcessor(LocalRouteResolver routeResolver, LocalHttpDispatcher dispatcher,
		ReplyPublisher replyPublisher, StringRedisTemplate redisTemplate, LocalMetrics metrics) {
		this(routeResolver, dispatcher, replyPublisher, redisTemplate, metrics, Clock.systemUTC());
	}

	LocalRequestProcessor(LocalRouteResolver routeResolver, LocalHttpDispatcher dispatcher,
		ReplyPublisher replyPublisher, StringRedisTemplate redisTemplate, LocalMetrics metrics, Clock clock) {
		this.routeResolver = routeResolver;
		this.dispatcher = dispatcher;
		this.replyPublisher = replyPublisher;
		this.redisTemplate = redisTemplate;
		this.metrics = metrics;
		this.clock = clock;
	}

	public void process(RecordId recordId, Map<String, String> fields) {
		StreamHttpRequest request;
		try {
			request = StreamHttpRequest.fromFields(fields);
		} catch (IllegalArgumentException e) {
			metrics.invalidRequest();
			log.error("잘못된 요청 엔트리 폐기. id={} cause={}", recordId, e.getMessage());
			acknowledge(recordId);
			return;
		}

		if (request.isExpired(clock.millis())) {
			metrics.expired();
			log.debug("만료된 요청 폐기. corrId={} {} {}", request.getCorrId(), request.getMethod(), request.getPath());
			acknowledge(recordId);
			return;
		}

		Timer.Sample sample = metrics.startDispatch();
		Optional<UmdProperties.Route> route = routeResolver.resolve(request.getPath());
		Optional<StreamHttpReply> reply = route.isEmpty()
			? Optional.of(dispatcher.error(request, UmdErrorCode.NO_ROUTE))
			: dispatcher.dispatch(request, route.get());
		metrics.recordDispatch(sample, request.getMethod(), route.map(UmdProperties.Route::getId).orElse("none"),
			reply.map(StreamHttpReply::getStatus).orElse(0), outcome(route, reply));

		if (reply.isPresent()) {
			try {
				replyPublisher.publish(request.getReplyTo(), reply.get());
			} catch (RuntimeException e) {
				log.warn("응답 발행 실패 (ACK 보류, 회수 대상). corrId={} cause={}", request.getCorrId(), e.getMessage());
				return;
			}
		}
		acknowledge(recordId);
	}

	private static String outcome(Optional<UmdProperties.Route> route, Optional<StreamHttpReply> reply) {
		if (route.isEmpty()) {
			return UmdErrorCode.NO_ROUTE.name();
		}
		if (reply.isEmpty()) {
			return UmdErrorCode.TIMEOUT.name();
		}
		String umdError = reply.get().header(UmdErrorCode.HEADER);
		return umdError == null ? "OK" : umdError.replaceFirst("^UMD_", "");
	}

	void acknowledge(RecordId recordId) {
		try {
			redisTemplate.opsForStream().acknowledge(StreamKeys.REQUEST_STREAM, StreamKeys.CONSUMER_GROUP, recordId);
			redisTemplate.opsForStream().delete(StreamKeys.REQUEST_STREAM, recordId);
		} catch (RuntimeException e) {
			log.warn("XACK/XDEL 실패. id={} cause={}", recordId, e.getMessage());
		}
	}
}
