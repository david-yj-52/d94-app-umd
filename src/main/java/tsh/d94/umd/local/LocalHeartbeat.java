package tsh.d94.umd.local;

import java.time.Duration;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * [local] 생존 신호: 5초마다 d94:umd:local:alive 를 SET EX 15.
 * server 는 이 키가 없으면 요청을 Redis 에 싣지 않고 즉시 503 을 준다 (30초 대기 방지).
 * 정상 종료 시 자기 값이면 즉시 삭제한다 (다른 local 인스턴스의 신호는 지우지 않음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class LocalHeartbeat implements SmartLifecycle {

	static final Duration TTL = Duration.ofSeconds(15);

	private final StringRedisTemplate redisTemplate;
	private final UmdProperties properties;
	private final RequestStreamConsumer consumer;

	private volatile boolean running;

	@Override
	public void start() {
		running = true;
		beat();
	}

	@Override
	public void stop() {
		running = false;
		try {
			if (properties.getInstanceId().equals(redisTemplate.opsForValue().get(StreamKeys.LOCAL_ALIVE_KEY))) {
				redisTemplate.delete(StreamKeys.LOCAL_ALIVE_KEY);
			}
		} catch (RuntimeException e) {
			log.debug("heartbeat 키 삭제 실패. cause={}", e.getMessage());
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/** consumer 보다 늦게 시작하고 먼저 멈춘다 (신호가 있는 동안은 항상 소비 중) */
	@Override
	public int getPhase() {
		return consumer.getPhase() + 1;
	}

	@Scheduled(fixedRate = 5_000)
	public void beat() {
		if (!running || !consumer.isRunning()) {
			return;
		}
		try {
			redisTemplate.opsForValue().set(StreamKeys.LOCAL_ALIVE_KEY, properties.getInstanceId(), TTL);
		} catch (RuntimeException e) {
			log.debug("heartbeat 실패. cause={}", e.getMessage());
		}
	}
}
