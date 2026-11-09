package tsh.d94.umd.common;

import java.time.Duration;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SocketOptions.KeepAliveOptions;

@Configuration
@EnableScheduling // server: 응답 Stream TTL 갱신 / local: heartbeat, Pending 회수
@EnableConfigurationProperties(UmdProperties.class)
public class UmdConfig {

	/**
	 * OCI 방화벽이 ~5분 유휴 TCP 연결을 끊으므로 Lettuce 소켓에 OS 수준 TCP keepalive 를 켠다.
	 * Redis 서버도 --tcp-keepalive 60 으로 설정되어 있어 양방향 keepalive 가 활성화된다.
	 *
	 * REJECT_COMMANDS: Redis 연결이 끊어진 동안 명령을 큐에 쌓지 않고 즉시 실패시킨다.
	 * 큐에 쌓인 명령은 spring.data.redis.timeout 타임아웃이 적용되지 않아 힙을 고갈시킬 수 있다.
	 * 호출 측(RequestStreamConsumer, ReplyStreamListener)이 이미 오류를 처리하므로 즉시 실패가 안전하다.
	 */
	@Bean
	public LettuceClientConfigurationBuilderCustomizer lettuceKeepaliveCustomizer() {
		return builder -> builder.clientOptions(
			ClientOptions.builder()
				.autoReconnect(true)
				.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
				.socketOptions(SocketOptions.builder()
					.keepAlive(KeepAliveOptions.builder()
						.enable()
						.idle(Duration.ofSeconds(60))
						.interval(Duration.ofSeconds(10))
						.count(3)
						.build())
					.build())
				.build());
	}
}
