package tsh.d94.umd.server;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;

/**
 * /actuator/health 의 replyStream 항목.
 * Redis 연결(redis 항목)과 별개로, 응답 수신 리스너가 실제로 동작 중인지 나타낸다.
 */
@Component("replyStream")
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class ReplyStreamHealthIndicator implements HealthIndicator {

	private final ReplyStreamListener listener;
	private final PendingReplyRegistry registry;
	private final UmdProperties properties;

	@Override
	public Health health() {
		Health.Builder builder = !listener.isRunning() || !listener.isReadHealthy()
			? Health.down()
			: Health.up();
		return builder
			.withDetail("stream", properties.getReplyStream())
			.withDetail("listening", listener.isRunning())
			.withDetail("readHealthy", listener.isReadHealthy())
			.withDetail("pending", registry.size())
			.build();
	}
}
