package tsh.d94.umd.common;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * 요청 Stream 의 Consumer Group 을 기동 시 생성한다 (양 모드 공통, 멱등).
 * <ul>
 *   <li>server 가 먼저 떠도 local 기동 전 요청이 유실되지 않는다.</li>
 *   <li>local 이 먼저 떠도 XREADGROUP 이 NOGROUP 오류 없이 시작된다.</li>
 * </ul>
 * XGROUP CREATE ... $ MKSTREAM, 이미 있으면 BUSYGROUP 무시. Redis 미연결 시 기동은 계속한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamGroupInitializer {

	private final StringRedisTemplate redisTemplate;

	@EventListener(ApplicationReadyEvent.class)
	public void ensureConsumerGroup() {
		try {
			redisTemplate.execute((RedisCallback<String>)connection -> connection.streamCommands().xGroupCreate(
				StreamKeys.REQUEST_STREAM.getBytes(),
				StreamKeys.CONSUMER_GROUP,
				ReadOffset.latest(),
				true));
			log.info("Consumer group created. stream={} group={}",
				StreamKeys.REQUEST_STREAM, StreamKeys.CONSUMER_GROUP);
		} catch (RedisSystemException e) {
			// RedisSystemException("Error in execution") 이 원인(BUSYGROUP)을 감싸므로 가장 안쪽 메시지로 판단한다.
			if (rootMessage(e).contains("BUSYGROUP")) {
				log.debug("Consumer group already exists. group={}", StreamKeys.CONSUMER_GROUP);
				return;
			}
			log.warn("Consumer group 생성 실패. stream={} cause={}", StreamKeys.REQUEST_STREAM, rootMessage(e));
		} catch (RuntimeException e) {
			log.warn("Consumer group 생성 실패 (Redis 미연결?). stream={} cause={}",
				StreamKeys.REQUEST_STREAM, rootMessage(e));
		}
	}

	/** 예외 체인의 가장 안쪽 메시지 (Redis 오류 코드 판별용) */
	public static String rootMessage(Throwable error) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(error);
		return String.valueOf(cause.getMessage());
	}
}
