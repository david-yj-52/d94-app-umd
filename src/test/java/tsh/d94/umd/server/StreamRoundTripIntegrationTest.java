package tsh.d94.umd.server;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tsh.d94.umd.common.spec.StreamKeys;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.support.FakeLocalConsumer;

/**
 * 실제 Redis + 가짜 local Consumer(echo)로 요청-응답 왕복을 검증한다.
 * Docker 가 없으면 skip 된다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
	"application.security.jwt.secret-key=c2VjcmV0LWtleS1mb3ItdW1kLWludGVncmF0aW9uLXRlc3Qtb25seS0xMjM0NTY=",
	"umd.instance-id=umd-it",
	"umd.server.poll-timeout=200ms"
})
class StreamRoundTripIntegrationTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	private StreamRequestGateway gateway;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private volatile boolean localReplies = true;
	private FakeLocalConsumer local;

	@BeforeEach
	void startFakeLocal() {
		local = new FakeLocalConsumer(redisTemplate, request -> !localReplies ? null : request.replyBuilder()
			.status(200)
			.headers(request.getHeaders())
			.body(("echo:" + new String(request.getBody(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8))
			.ts(System.currentTimeMillis())
			.build());
	}

	@AfterEach
	void stopFakeLocal() throws InterruptedException {
		local.close();
	}

	private static final Map<String, List<String>> TEXT = Map.of("content-type", List.of("text/plain"));

	private StreamCall send(String text) {
		return gateway.request("POST", "/v1/echo", null, TEXT, text.getBytes(StandardCharsets.UTF_8));
	}

	private static String text(StreamHttpReply reply) {
		return new String(reply.getBody(), StandardCharsets.UTF_8);
	}

	@Test
	void request_reply_round_trip() throws Exception {
		StreamHttpReply reply = send("ping").reply()
			.get(10, TimeUnit.SECONDS);

		assertThat(reply.getStatus()).isEqualTo(200);
		assertThat(text(reply)).isEqualTo("echo:ping");
		// 처리된 응답 엔트리는 XDEL 로 정리된다 (Future 완료 직후 수행되므로 잠시 대기)
		long deadline = System.currentTimeMillis() + 2_000;
		while (redisTemplate.opsForStream().size(StreamKeys.replyStream("umd-it")) > 0
			&& System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertThat(redisTemplate.opsForStream().size(StreamKeys.replyStream("umd-it"))).isZero();
	}

	@Test
	void concurrent_requests_are_matched_by_corrId() throws Exception {
		var futures = java.util.stream.IntStream.range(0, 20)
			.mapToObj(i -> send("p" + i).reply())
			.toList();

		for (int i = 0; i < futures.size(); i++) {
			assertThat(text(futures.get(i).get(10, TimeUnit.SECONDS))).isEqualTo("echo:p" + i);
		}
	}

	@Test
	void no_reply_times_out() {
		localReplies = false;

		assertThatThrownBy(() -> gateway.request("POST", "/v1/echo", null, TEXT, "x".getBytes(StandardCharsets.UTF_8),
			Duration.ofMillis(500)).reply()
			.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(TimeoutException.class);
	}
}
