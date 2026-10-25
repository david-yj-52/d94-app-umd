package tsh.d94.umd.server.proxy;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tsh.d94.lib.core.apService.auth.JwtService;
import tsh.d94.umd.common.spec.StreamField;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;
import tsh.d94.umd.common.spec.StreamKeys;
import tsh.d94.umd.support.FakeLocalConsumer;

/**
 * 실제 HTTP → UMD(server, /** 프록시) → Redis → 가짜 local → Redis → UMD → HTTP 응답.
 * 가짜 local 은 경로별로 백엔드 동작을 흉내 낸다. (실제 local 모드까지의 종단 검증은 EndToEndBothModeTest)
 */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"application.security.jwt.secret-key=c2VjcmV0LWtleS1mb3ItdW1kLWludGVncmF0aW9uLXRlc3Qtb25seS0xMjM0NTY=",
	"umd.mode=server",
	"umd.instance-id=umd-proxy-it",
	"umd.server.poll-timeout=200ms",
	"umd.server.request-timeout=1s",
	"umd.server.max-request-body=1KB",
	"umd.server.cors-origins=https://ui.example.com",
	"management.server.port=0"
})
class ServerProxyIntegrationTest {

	private static final byte[] PNG = {(byte)0x89, 'P', 'N', 'G', 0, (byte)0xFF, 1, 2};

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@LocalServerPort
	private int port;

	@LocalManagementPort
	private int managementPort;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JwtService jwtService;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final HttpClient http = HttpClient.newHttpClient();
	private FakeLocalConsumer local;
	private String token;

	@BeforeEach
	void setUp() {
		redisTemplate.opsForValue().set(StreamKeys.LOCAL_ALIVE_KEY, "fake-local");
		local = new FakeLocalConsumer(redisTemplate, this::backend);
		token = jwtService.generateAccessToken(User.withUsername("tester@d94.local").password("x").roles("USER").build());
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		local.close();
	}

	// ---------- 가짜 local + 백엔드 ----------

	private StreamHttpReply backend(StreamHttpRequest req) {
		StreamHttpReply.StreamHttpReplyBuilder reply = req.replyBuilder().ts(System.currentTimeMillis());
		if ("OPTIONS".equals(req.getMethod())) {
			return reply.status(204).headers(Map.of(
				"access-control-allow-origin", List.of(req.header("origin")),
				"access-control-allow-methods", List.of("GET,POST,PUT,PATCH,DELETE"))).build();
		}
		return switch (req.getPath()) {
			case "/v1/items" -> reply.status(201)
				.headers(Map.of("location", List.of("/v1/items/43"))).build();
			case "/v1/items/1" -> reply.status(204).build();
			case "/v1/binary" -> reply.status(200)
				.headers(Map.of("content-type", List.of("image/png"))).body(PNG).build();
			case "/v1/missing" -> reply.status(404)
				.headers(Map.of("content-type", List.of("application/json")))
				.body("{\"code\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8)).build();
			case "/v1/etag" -> "\"v1\"".equals(req.header("if-none-match"))
				? reply.status(304).headers(Map.of("etag", List.of("\"v1\""))).build()
				: reply.status(200).headers(Map.of("etag", List.of("\"v1\""))).build();
			case "/v1/silent" -> null;
			case "/v1/bad" -> {
				Map<String, String> bad = new LinkedHashMap<>();
				bad.put(StreamField.CORR_ID, req.getCorrId());
				bad.put(StreamField.STATUS, "not-a-number");
				bad.put(StreamField.TS, "1");
				redisTemplate.opsForStream().add(StreamRecords.newRecord().in(req.getReplyTo()).ofMap(bad));
				yield null;
			}
			default -> echo(req, reply);
		};
	}

	private StreamHttpReply echo(StreamHttpRequest req, StreamHttpReply.StreamHttpReplyBuilder reply) {
		Map<String, Object> echo = new LinkedHashMap<>();
		echo.put("method", req.getMethod());
		echo.put("path", req.getPath());
		echo.put("query", req.getQuery());
		echo.put("headers", req.getHeaders());
		echo.put("body", new String(req.getBody(), StandardCharsets.UTF_8));
		try {
			return reply.status(200)
				.headers(Map.of("content-type", List.of("application/json"), "x-backend", List.of("1")))
				.body(objectMapper.writeValueAsBytes(echo)).build();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// ---------- 호출 도우미 ----------

	private HttpRequest.Builder req(String pathAndQuery, boolean withToken) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
			.timeout(Duration.ofSeconds(10));
		if (withToken) {
			builder.header("Authorization", "Bearer " + token);
		}
		return builder;
	}

	private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
		return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private JsonNode json(HttpResponse<byte[]> res) throws Exception {
		return objectMapper.readTree(res.body());
	}

	private long requestStreamSize() {
		Long size = redisTemplate.opsForStream().size(StreamKeys.REQUEST_STREAM);
		return size == null ? 0 : size;
	}

	// ---------- 정상 전달 ----------

	@Test
	void get_reaches_local_with_same_path_query_and_headers() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/echo?page=2&size=10", true)
			.header("Cookie", "session=1")
			.header("X-Forwarded-Proto", "spoofed")
			.header("X-Trace-Id", "spoofed")
			.GET().build());

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.headers().firstValue("x-backend")).hasValue("1");
		String traceId = res.headers().firstValue("X-Trace-Id").orElseThrow();
		JsonNode echo = json(res);
		assertThat(echo.path("method").asText()).isEqualTo("GET");
		assertThat(echo.path("path").asText()).isEqualTo("/v1/echo");
		assertThat(echo.path("query").asText()).isEqualTo("page=2&size=10");
		JsonNode headers = echo.path("headers");
		assertThat(headers.path("authorization").get(0).asText()).isEqualTo("Bearer " + token);
		assertThat(headers.path("x-trace-id").get(0).asText()).isEqualTo(traceId);
		assertThat(headers.path("x-forwarded-proto").get(0).asText()).isEqualTo("http");
		assertThat(headers.path("x-forwarded-for").get(0).asText()).isEqualTo("127.0.0.1");
		assertThat(headers.has("cookie")).isFalse();
	}

	@Test
	void post_returns_201_with_location() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/items", true)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"a\"}")).build());

		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(res.headers().firstValue("location")).hasValue("/v1/items/43");
	}

	@Test
	void put_patch_and_body_are_forwarded() throws Exception {
		for (String method : List.of("PUT", "PATCH")) {
			HttpResponse<byte[]> res = send(req("/v1/echo", true)
				.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString("{\"v\":1}")).build());

			assertThat(json(res).path("method").asText()).isEqualTo(method);
			assertThat(json(res).path("body").asText()).isEqualTo("{\"v\":1}");
		}
	}

	@Test
	void delete_204_has_no_body() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/items/1", true).DELETE().build());

		assertThat(res.statusCode()).isEqualTo(204);
		assertThat(res.body()).isEmpty();
	}

	@Test
	void binary_response_bytes_are_preserved() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/binary", true).GET().build());

		assertThat(res.headers().firstValue("content-type")).hasValue("image/png");
		assertThat(res.body()).isEqualTo(PNG);
	}

	@Test
	void backend_error_is_passed_through_unwrapped() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/missing", true).GET().build());

		assertThat(res.statusCode()).isEqualTo(404);
		assertThat(new String(res.body(), StandardCharsets.UTF_8)).isEqualTo("{\"code\":\"NOT_FOUND\"}");
		assertThat(res.headers().firstValue("X-Umd-Error")).isEmpty();
	}

	@Test
	void conditional_get_returns_304() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/etag", true).header("If-None-Match", "\"v1\"").GET().build());

		assertThat(res.statusCode()).isEqualTo(304);
		assertThat(res.headers().firstValue("etag")).hasValue("\"v1\"");
	}

	@Test
	void head_returns_status_without_body() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/echo", true).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).isEmpty();
	}

	@Test
	void cors_preflight_is_forwarded_without_token() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/echo", false)
			.header("Origin", "https://ui.example.com")
			.header("Access-Control-Request-Method", "POST")
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build());

		assertThat(res.statusCode()).isEqualTo(204);
		assertThat(res.headers().firstValue("access-control-allow-origin")).hasValue("https://ui.example.com");
	}

	// ---------- UMD 자체 오류 ----------

	@Test
	void missing_token_is_401_and_not_published() throws Exception {
		long before = requestStreamSize();

		HttpResponse<byte[]> res = send(req("/v1/echo", false).GET().build());

		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(json(res).path("code").asText()).isEqualTo("UMD_UNAUTHORIZED");
		assertThat(res.headers().firstValue("X-Umd-Error")).hasValue("UMD_UNAUTHORIZED");
		assertThat(requestStreamSize()).isEqualTo(before);
	}

	@Test
	void umd_error_has_cors_headers_only_for_allowed_origin() throws Exception {
		HttpResponse<byte[]> allowed = send(req("/v1/echo", false).header("Origin", "https://ui.example.com").GET().build());
		HttpResponse<byte[]> other = send(req("/v1/echo", false).header("Origin", "https://evil.example.com").GET().build());

		assertThat(allowed.headers().firstValue("access-control-allow-origin")).hasValue("https://ui.example.com");
		assertThat(allowed.headers().firstValue("access-control-expose-headers").orElse("")).contains("X-Umd-Error");
		assertThat(other.headers().firstValue("access-control-allow-origin")).isEmpty();
	}

	@Test
	void too_large_body_is_413_and_not_published() throws Exception {
		long before = requestStreamSize();

		HttpResponse<byte[]> res = send(req("/v1/echo", true)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("x".repeat(2048))).build());

		assertThat(res.statusCode()).isEqualTo(413);
		assertThat(json(res).path("code").asText()).isEqualTo("UMD_PAYLOAD_TOO_LARGE");
		assertThat(requestStreamSize()).isEqualTo(before);
	}

	@Test
	void no_reply_is_504_with_trace_id() throws Exception {
		HttpResponse<byte[]> res = send(req("/v1/silent", true).GET().build());

		assertThat(res.statusCode()).isEqualTo(504);
		assertThat(json(res).path("code").asText()).isEqualTo("UMD_TIMEOUT");
		assertThat(res.headers().firstValue("X-Trace-Id")).isPresent();
	}

	@Test
	void malformed_reply_is_502_without_waiting_for_timeout() throws Exception {
		long started = System.currentTimeMillis();

		HttpResponse<byte[]> res = send(req("/v1/bad", true).GET().build());

		assertThat(res.statusCode()).isEqualTo(502);
		assertThat(json(res).path("code").asText()).isEqualTo("UMD_BAD_REPLY");
		assertThat(System.currentTimeMillis() - started).isLessThan(1_000);
	}

	@Test
	void missing_local_heartbeat_is_503_immediately() throws Exception {
		redisTemplate.delete(StreamKeys.LOCAL_ALIVE_KEY);
		Thread.sleep(1_100); // LocalAliveChecker 1초 캐시
		try {
			long before = requestStreamSize();

			HttpResponse<byte[]> res = send(req("/v1/echo", true).GET().build());

			assertThat(res.statusCode()).isEqualTo(503);
			assertThat(json(res).path("code").asText()).isEqualTo("UMD_LOCAL_UNAVAILABLE");
			assertThat(requestStreamSize()).isEqualTo(before);
		} finally {
			redisTemplate.opsForValue().set(StreamKeys.LOCAL_ALIVE_KEY, "fake-local");
			Thread.sleep(1_100);
		}
	}

	// ---------- actuator ----------

	@Test
	void actuator_path_on_app_port_is_proxied_not_served() throws Exception {
		assertThat(send(req("/actuator/health", false).GET().build()).statusCode()).isEqualTo(401);
	}

	@Test
	void management_port_exposes_proxy_metrics() throws Exception {
		send(req("/v1/echo", true).GET().build());

		HttpResponse<String> res = http.send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).GET().build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
			.contains("umd_proxy_request_seconds_count{method=\"GET\",outcome=\"OK\",status=\"2xx\"}")
			.contains("umd_stream_pending");
	}
}
