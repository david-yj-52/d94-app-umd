package tsh.d94.umd;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.MeterRegistry;
import tsh.d94.lib.core.apService.auth.JwtService;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * mode=both 종단 테스트: HTTP → UMD(server) → Redis → UMD(local) → 스텁 백엔드(JDK HttpServer) → 역방향.
 * 마지막에는 같은 요청을 백엔드에 직접 보내 결과가 같은지(직결 동등성)도 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"application.security.jwt.secret-key=c2VjcmV0LWtleS1mb3ItdW1kLWludGVncmF0aW9uLXRlc3Qtb25seS0xMjM0NTY=",
	"umd.mode=both",
	"umd.instance-id=umd-e2e",
	"umd.server.request-timeout=1s",
	"umd.server.poll-timeout=200ms",
	"umd.local.poll-timeout=200ms",
	"umd.local.max-response-body=4KB",
	"management.server.port=0"
})
class EndToEndBothModeTest {

	private static final byte[] PNG = {(byte)0x89, 'P', 'N', 'G', 0, (byte)0xFF, 1, 2};
	private static final ObjectMapper JSON = new ObjectMapper();

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	static final HttpServer BACKEND = startBackend();
	static final HttpServer BACKEND_2 = startBackend2();
	static final int CLOSED_PORT = closedPort();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("umd.local.routes[0].id", () -> "v2");
		registry.add("umd.local.routes[0].path-prefix", () -> "/v2");
		registry.add("umd.local.routes[0].uri", () -> "http://127.0.0.1:" + BACKEND_2.getAddress().getPort());
		registry.add("umd.local.routes[1].id", () -> "down");
		registry.add("umd.local.routes[1].path-prefix", () -> "/v3");
		registry.add("umd.local.routes[1].uri", () -> "http://127.0.0.1:" + CLOSED_PORT);
		registry.add("umd.local.routes[2].id", () -> "default");
		registry.add("umd.local.routes[2].path-prefix", () -> "/");
		registry.add("umd.local.routes[2].uri", () -> backendBase());
	}

	@LocalServerPort
	private int port;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private MeterRegistry meterRegistry;

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
	private String token;

	@BeforeEach
	void setUp() {
		token = jwtService.generateAccessToken(User.withUsername("tester@d94.local").password("x").roles("USER").build());
	}

	@AfterAll
	static void stopBackends() {
		BACKEND.stop(0);
		BACKEND_2.stop(0);
	}

	// ---------- 스텁 백엔드 ----------

	private static String backendBase() {
		return "http://127.0.0.1:" + BACKEND.getAddress().getPort();
	}

	private static HttpServer startBackend() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.createContext("/", EndToEndBothModeTest::handle);
			server.start();
			return server;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static HttpServer startBackend2() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", ex -> respond(ex, 200, "backend-2".getBytes(StandardCharsets.UTF_8),
				Map.of("Content-Type", "text/plain")));
			server.start();
			return server;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static int closedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void handle(HttpExchange ex) throws IOException {
		byte[] body = ex.getRequestBody().readAllBytes();
		Headers in = ex.getRequestHeaders();
		if ("OPTIONS".equals(ex.getRequestMethod())) {
			respond(ex, 204, new byte[0], Map.of(
				"Access-Control-Allow-Origin", String.valueOf(in.getFirst("Origin")),
				"Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE"));
			return;
		}
		switch (ex.getRequestURI().getPath()) {
			case "/v1/items" -> respond(ex, 201, "{\"id\":43}".getBytes(StandardCharsets.UTF_8),
				Map.of("Location", "/v1/items/43", "Content-Type", "application/json"));
			case "/v1/items/1" -> respond(ex, 204, new byte[0], Map.of());
			case "/v1/invalid" -> respond(ex, 400, "{\"code\":\"VALIDATION\",\"field\":\"name\"}"
				.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json"));
			case "/v1/etag" -> {
				if ("\"v1\"".equals(in.getFirst("If-None-Match"))) {
					respond(ex, 304, new byte[0], Map.of("ETag", "\"v1\""));
				} else {
					respond(ex, 200, "fresh".getBytes(StandardCharsets.UTF_8), Map.of("ETag", "\"v1\""));
				}
			}
			case "/v1/binary" -> respond(ex, 200, PNG, Map.of("Content-Type", "image/png"));
			case "/v1/redirect" -> respond(ex, 302, new byte[0], Map.of("Location", "/v1/echo"));
			case "/v1/big" -> respond(ex, 200, "x".repeat(5_000).getBytes(StandardCharsets.UTF_8),
				Map.of("Content-Type", "text/plain"));
			case "/v1/slow" -> {
				try {
					Thread.sleep(1_500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				respond(ex, 200, "late".getBytes(StandardCharsets.UTF_8), Map.of());
			}
			default -> {
				Map<String, Object> echo = new LinkedHashMap<>();
				echo.put("method", ex.getRequestMethod());
				echo.put("path", ex.getRequestURI().getRawPath());
				echo.put("query", ex.getRequestURI().getRawQuery());
				Map<String, String> headers = new LinkedHashMap<>();
				in.forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
				echo.put("headers", headers);
				echo.put("body", new String(body, StandardCharsets.UTF_8));
				respond(ex, 200, JSON.writeValueAsBytes(echo), Map.of("Content-Type", "application/json"));
			}
		}
	}

	private static void respond(HttpExchange ex, int status, byte[] body, Map<String, String> headers)
		throws IOException {
		headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
		boolean noBody = body.length == 0 || status == 204 || status == 304 || "HEAD".equals(ex.getRequestMethod());
		ex.sendResponseHeaders(status, noBody ? -1 : body.length);
		try (OutputStream out = ex.getResponseBody()) {
			if (!noBody) {
				out.write(body);
			}
		}
	}

	// ---------- 호출 도우미 ----------

	private HttpRequest.Builder viaUmd(String pathAndQuery) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + token);
	}

	private HttpRequest.Builder direct(String pathAndQuery) {
		return HttpRequest.newBuilder(URI.create(backendBase() + pathAndQuery))
			.timeout(Duration.ofSeconds(10))
			.header("Authorization", "Bearer " + token);
	}

	private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
		return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String text(HttpResponse<byte[]> res) {
		return new String(res.body(), StandardCharsets.UTF_8);
	}

	// ---------- 시나리오 ----------

	@Test
	void get_reaches_backend_with_same_path_query_and_auth() throws Exception {
		HttpResponse<byte[]> res = send(viaUmd("/v1/echo?page=2&size=10").GET().build());

		assertThat(res.statusCode()).isEqualTo(200);
		JsonNode echo = JSON.readTree(res.body());
		assertThat(echo.path("method").asText()).isEqualTo("GET");
		assertThat(echo.path("path").asText()).isEqualTo("/v1/echo");
		assertThat(echo.path("query").asText()).isEqualTo("page=2&size=10");
		JsonNode headers = echo.path("headers");
		assertThat(headers.path("authorization").asText()).isEqualTo("Bearer " + token);
		assertThat(headers.path("x-trace-id").asText()).isEqualTo(res.headers().firstValue("X-Trace-Id").orElseThrow());
		assertThat(headers.path("x-forwarded-for").asText()).isEqualTo("127.0.0.1");
		assertThat(headers.path("x-forwarded-proto").asText()).isEqualTo("http");
		assertThat(headers.path("host").asText()).isEqualTo("127.0.0.1:" + BACKEND.getAddress().getPort());
	}

	@Test
	void post_put_patch_delete_are_forwarded() throws Exception {
		HttpResponse<byte[]> created = send(viaUmd("/v1/items").header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"a\"}")).build());
		assertThat(created.statusCode()).isEqualTo(201);
		assertThat(created.headers().firstValue("location")).hasValue("/v1/items/43");

		for (String method : List.of("PUT", "PATCH")) {
			HttpResponse<byte[]> res = send(viaUmd("/v1/echo").header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString("{\"v\":1}")).build());
			JsonNode echo = JSON.readTree(res.body());
			assertThat(echo.path("method").asText()).isEqualTo(method);
			assertThat(echo.path("body").asText()).isEqualTo("{\"v\":1}");
		}

		HttpResponse<byte[]> deleted = send(viaUmd("/v1/items/1").DELETE().build());
		assertThat(deleted.statusCode()).isEqualTo(204);
		assertThat(deleted.body()).isEmpty();
	}

	@Test
	void backend_errors_redirects_and_conditional_get_pass_through() throws Exception {
		HttpResponse<byte[]> invalid = send(viaUmd("/v1/invalid").GET().build());
		assertThat(invalid.statusCode()).isEqualTo(400);
		assertThat(text(invalid)).isEqualTo("{\"code\":\"VALIDATION\",\"field\":\"name\"}");
		assertThat(invalid.headers().firstValue("X-Umd-Error")).isEmpty();

		HttpResponse<byte[]> redirect = send(viaUmd("/v1/redirect").GET().build());
		assertThat(redirect.statusCode()).isEqualTo(302);
		assertThat(redirect.headers().firstValue("location")).hasValue("/v1/echo");

		HttpResponse<byte[]> notModified = send(viaUmd("/v1/etag").header("If-None-Match", "\"v1\"").GET().build());
		assertThat(notModified.statusCode()).isEqualTo(304);
	}

	@Test
	void binary_body_is_preserved() throws Exception {
		HttpResponse<byte[]> res = send(viaUmd("/v1/binary").GET().build());

		assertThat(res.headers().firstValue("content-type")).hasValue("image/png");
		assertThat(res.body()).isEqualTo(PNG);
	}

	@Test
	void routes_by_longest_prefix() throws Exception {
		assertThat(text(send(viaUmd("/v2/anything").GET().build()))).isEqualTo("backend-2");
	}

	@Test
	void unreachable_backend_is_502() throws Exception {
		HttpResponse<byte[]> res = send(viaUmd("/v3/items").GET().build());

		assertThat(res.statusCode()).isEqualTo(502);
		assertThat(res.headers().firstValue("X-Umd-Error")).hasValue("UMD_LOCAL_BACKEND_UNAVAILABLE");
	}

	@Test
	void too_large_backend_response_is_502() throws Exception {
		HttpResponse<byte[]> res = send(viaUmd("/v1/big").GET().build());

		assertThat(res.statusCode()).isEqualTo(502);
		assertThat(res.headers().firstValue("X-Umd-Error")).hasValue("UMD_RESPONSE_TOO_LARGE");
	}

	@Test
	void slow_backend_is_504_and_local_sends_no_late_reply() throws Exception {
		double lateBefore = meterRegistry.counter("umd.stream.reply.late").count();

		HttpResponse<byte[]> res = send(viaUmd("/v1/slow").GET().build());
		assertThat(res.statusCode()).isEqualTo(504);

		Thread.sleep(1_500); // 백엔드가 늦게 끝나도 local 은 deadline 이후라 응답하지 않는다
		assertThat(meterRegistry.counter("umd.stream.reply.late").count()).isEqualTo(lateBefore);
	}

	@Test
	void cors_preflight_is_answered_by_backend() throws Exception {
		HttpResponse<byte[]> res = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/items"))
			.header("Origin", "https://ui.example.com")
			.header("Access-Control-Request-Method", "POST")
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build());

		assertThat(res.statusCode()).isEqualTo(204);
		assertThat(res.headers().firstValue("access-control-allow-origin")).hasValue("https://ui.example.com");
	}

	@Test
	void processed_request_entries_do_not_remain_in_redis() throws Exception {
		send(viaUmd("/v1/echo").GET().build());

		long deadline = System.currentTimeMillis() + 2_000;
		while (System.currentTimeMillis() < deadline
			&& redisTemplate.opsForStream().size(StreamKeys.REQUEST_STREAM) > 0) {
			Thread.sleep(50);
		}
		assertThat(redisTemplate.opsForStream().size(StreamKeys.REQUEST_STREAM)).isZero();
		assertThat(redisTemplate.opsForStream()
			.pending(StreamKeys.REQUEST_STREAM, StreamKeys.CONSUMER_GROUP).getTotalPendingMessages()).isZero();
	}

	@Test
	void local_heartbeat_is_published() {
		assertThat(redisTemplate.opsForValue().get(StreamKeys.LOCAL_ALIVE_KEY)).isEqualTo("umd-e2e");
		assertThat(redisTemplate.getExpire(StreamKeys.LOCAL_ALIVE_KEY)).isBetween(1L, 15L);
	}

	@Test
	void same_result_via_umd_and_direct() throws Exception {
		List<HttpRequest.Builder[]> pairs = List.of(
			new HttpRequest.Builder[] {viaUmd("/v1/invalid").GET(), direct("/v1/invalid").GET()},
			new HttpRequest.Builder[] {viaUmd("/v1/binary").GET(), direct("/v1/binary").GET()},
			new HttpRequest.Builder[] {
				viaUmd("/v1/items").header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{}")),
				direct("/v1/items").header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{}"))},
			new HttpRequest.Builder[] {viaUmd("/v1/items/1").DELETE(), direct("/v1/items/1").DELETE()});

		for (HttpRequest.Builder[] pair : pairs) {
			HttpResponse<byte[]> umd = send(pair[0].build());
			HttpResponse<byte[]> raw = send(pair[1].build());

			assertThat(umd.statusCode()).isEqualTo(raw.statusCode());
			assertThat(umd.headers().firstValue("content-type")).isEqualTo(raw.headers().firstValue("content-type"));
			assertThat(umd.headers().firstValue("location")).isEqualTo(raw.headers().firstValue("location"));
			assertThat(umd.body()).isEqualTo(raw.body());
		}
	}

	@Test
	void local_dispatch_metrics_are_recorded() throws Exception {
		send(viaUmd("/v2/x").GET().build());

		assertThat(meterRegistry.find("umd.local.dispatch").tag("route", "v2").tag("outcome", "OK").timer())
			.isNotNull();
	}
}
