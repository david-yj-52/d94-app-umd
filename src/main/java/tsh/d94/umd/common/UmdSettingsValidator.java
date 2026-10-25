package tsh.d94.umd.common;

import java.time.Duration;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 기동 시 설정 조합 검사 (잘못되면 기동 실패).
 * <ul>
 *   <li>XREAD/XREADGROUP BLOCK 시간(poll-timeout) 은 Redis 명령 타임아웃(spring.data.redis.timeout)보다 짧아야 한다.
 *       같거나 길면 메시지가 없을 때마다 "Redis command timed out" 으로 읽기가 실패한다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class UmdSettingsValidator {

	private final UmdProperties properties;
	private final RedisProperties redisProperties;

	@PostConstruct
	void validate() {
		Duration commandTimeout = redisProperties.getTimeout();
		if (commandTimeout == null) {
			return;
		}
		check("umd.server.poll-timeout", properties.getServer().getPollTimeout(), commandTimeout);
		check("umd.local.poll-timeout", properties.getLocal().getPollTimeout(), commandTimeout);
	}

	private static void check(String name, Duration pollTimeout, Duration commandTimeout) {
		if (pollTimeout.compareTo(commandTimeout) >= 0) {
			throw new IllegalStateException(name + "(" + pollTimeout + ") 는 spring.data.redis.timeout("
				+ commandTimeout + ") 보다 짧아야 합니다.");
		}
	}
}
