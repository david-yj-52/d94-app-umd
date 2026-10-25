package tsh.d94.umd.local;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;

/**
 * /actuator/health 의 requestConsumer 항목 — 소비 루프가 동작 중이고 최근 10초 안에 읽기 실패가 없으면 UP.
 */
@Component("requestConsumer")
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class RequestConsumerHealthIndicator implements HealthIndicator {

	private final RequestStreamConsumer consumer;

	@Override
	public Health health() {
		Health.Builder builder = consumer.isRunning() && consumer.isReadHealthy() ? Health.up() : Health.down();
		return builder
			.withDetail("consuming", consumer.isRunning())
			.withDetail("readHealthy", consumer.isReadHealthy())
			.build();
	}
}
