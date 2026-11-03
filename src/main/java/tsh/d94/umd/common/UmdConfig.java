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
	 */
	@Bean
	public LettuceClientConfigurationBuilderCustomizer lettuceKeepaliveCustomizer() {
		return builder -> builder.clientOptions(
			ClientOptions.builder()
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
