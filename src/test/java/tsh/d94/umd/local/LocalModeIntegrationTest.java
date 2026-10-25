package tsh.d94.umd.local;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tsh.d94.umd.common.spec.StreamKeys;
import tsh.d94.umd.server.ReplyStreamListener;
import tsh.d94.umd.server.StreamRequestGateway;
import tsh.d94.umd.server.security.ServerSecurityConfig;

/**
 * mode=local: server Bean 이 없고, 앱 포트는 전부 거부, actuator 와 Consumer Group 생성은 동작.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"umd.mode=local",
	"umd.instance-id=umd-home-it",
	"management.server.port=0"
})
class LocalModeIntegrationTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	private ApplicationContext context;

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@LocalManagementPort
	private int managementPort;

	@Test
	void server_beans_are_not_registered() {
		assertThat(context.getBeanNamesForType(StreamRequestGateway.class)).isEmpty();
		assertThat(context.getBeanNamesForType(ReplyStreamListener.class)).isEmpty();
		assertThat(context.getBeanNamesForType(ServerSecurityConfig.class)).isEmpty();
		assertThat(context.getBeanNamesForType(LocalSecurityConfig.class)).hasSize(1);
	}

	@Test
	void app_port_rejects_everything() {
		assertThat(rest.getForEntity("/v1/items", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void actuator_health_is_up_on_management_port() {
		assertThat(rest.getForEntity("http://localhost:" + managementPort + "/actuator/health", String.class)
			.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void actuator_info_shows_mode_and_instance() {
		String info = rest.getForObject("http://localhost:" + managementPort + "/actuator/info", String.class);

		assertThat(info).contains("\"mode\":\"local\"").contains("\"instance-id\":\"umd-home-it\"");
	}

	@Test
	void consumer_group_is_created_on_startup() {
		assertThat(redisTemplate.opsForStream().groups(StreamKeys.REQUEST_STREAM))
			.anySatisfy(group -> assertThat(group.groupName()).isEqualTo(StreamKeys.CONSUMER_GROUP));
	}

	@Test
	void local_components_run_and_heartbeat_is_published() {
		assertThat(context.getBean(RequestStreamConsumer.class).isRunning()).isTrue();
		assertThat(redisTemplate.opsForValue().get(StreamKeys.LOCAL_ALIVE_KEY)).isEqualTo("umd-home-it");
	}
}
