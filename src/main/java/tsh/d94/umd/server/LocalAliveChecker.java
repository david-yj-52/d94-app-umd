package tsh.d94.umd.server;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * local 생존 신호(d94:umd:local:alive) 확인. 요청마다 Redis 를 조회하지 않도록 1초 캐시한다.
 * Redis 오류는 그대로 던진다 (호출 측에서 REDIS_UNAVAILABLE 로 처리).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class LocalAliveChecker {

	static final long CACHE_MILLIS = 1_000L;

	private final StringRedisTemplate redisTemplate;

	private volatile long checkedAt;
	private volatile boolean alive;

	public boolean isAlive() {
		long now = System.currentTimeMillis();
		if (now - checkedAt < CACHE_MILLIS) {
			return alive;
		}
		alive = Boolean.TRUE.equals(redisTemplate.hasKey(StreamKeys.LOCAL_ALIVE_KEY));
		checkedAt = now;
		return alive;
	}

	/** 마지막 확인 결과 (Redis 조회 없음, health 정보용) */
	public boolean lastKnownAlive() {
		return alive;
	}
}
