package tsh.d94.umd;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tsh.d94.umd.local.LocalSecurityConfig;
import tsh.d94.umd.local.RequestStreamConsumer;
import tsh.d94.umd.server.StreamRequestGateway;

/**
 * mode=both: server Bean 과 server 보안만 등록되고, local 전용 보안과 충돌하지 않는다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
	"umd.mode=both",
	"umd.instance-id=umd-both-it",
	"application.security.jwt.secret-key=c2VjcmV0LWtleS1mb3ItdW1kLWludGVncmF0aW9uLXRlc3Qtb25seS0xMjM0NTY="
})
class BothModeContextTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	private ApplicationContext context;

	@Test
	void server_beans_and_single_security_chain() {
		assertThat(context.getBeanNamesForType(StreamRequestGateway.class)).hasSize(1);
		assertThat(context.getBeanNamesForType(RequestStreamConsumer.class)).hasSize(1);
		assertThat(context.getBeanNamesForType(LocalSecurityConfig.class)).isEmpty();
		assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
	}
}
