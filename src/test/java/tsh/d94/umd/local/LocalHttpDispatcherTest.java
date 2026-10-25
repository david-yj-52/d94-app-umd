package tsh.d94.umd.local;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;

class LocalHttpDispatcherTest {

	private static HttpServer backend;
	private static final AtomicInteger hits = new AtomicInteger();
	private static final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
	private static final AtomicReference<String> lastBody = new AtomicReference<>();

	private final UmdProperties properties = new UmdProperties();
	private final LocalHttpDispatcher dispatcher = new LocalHttpDispatcher(
		HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), properties, Clock.systemUTC());

	@BeforeAll
	static void startBackend() throws IOException {
		backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		backend.setExecutor(Executors.newCachedThreadPool());
		backend.createContext("/", exchange -> {
			hits.incrementAndGet();
			lastExchange.set(exchange);
			lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			switch (exchange.getRequestURI().getPath()) {
				case "/ctx/v1/ok" -> respond(exchange, 201, "created", Map.of("Location", "/v1/items/1", "Content-Type", "text/plain"));
				case "/ctx/v1/redirect" -> respond(exchange, 302, "", Map.of("Location", "/elsewhere"));
				case "/ctx/v1/slow" -> {
					sleep(1_500);
					respond(exchange, 200, "late", Map.of());
				}
				case "/ctx/v1/big" -> respond(exchange, 200, "x".repeat(2048), Map.of("Content-Type", "text/plain"));
				default -> respond(exchange, 404, "{}", Map.of("Content-Type", "application/json"));
			}
		});
		backend.start();
	}

	@AfterAll
	static void stopBackend() {
		backend.stop(0);
	}

	private static void respond(HttpExchange exchange, int status, String body, Map<String, String> headers)
		throws IOException {
		headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static UmdProperties.Route route(URI uri) {
		UmdProperties.Route route = new UmdProperties.Route();
		route.setId("r");
		route.setPathPrefix("/");
		route.setUri(uri);
		return route;
	}

	private UmdProperties.Route backendRoute() {
		return route(URI.create("http://127.0.0.1:" + backend.getAddress().getPort() + "/ctx"));
	}

	private StreamHttpRequest request(String method, String path, String body, long timeoutMillis) {
		long now = System.currentTimeMillis();
		return StreamHttpRequest.builder()
			.corrId("c1").replyTo("r").method(method).path(path).query("a=1")
			.headers(Map.of(
				"content-type", List.of("application/json"),
				"authorization", List.of("Bearer t"),
				"host", List.of("ap01.example.com"),
				"expect", List.of("100-continue"),
				"x-forwarded-for", List.of("203.0.113.7")))
			.body(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8))
			.ts(now).deadline(now + timeoutMillis)
			.build();
	}

	@Test
	void calls_backend_with_same_path_query_headers_and_body() {
		Optional<StreamHttpReply> reply = dispatcher.dispatch(request("POST", "/v1/ok", "{\"a\":1}", 5_000),
			backendRoute());

		assertThat(reply).isPresent();
		assertThat(reply.get().getStatus()).isEqualTo(201);
		assertThat(reply.get().header("location")).isEqualTo("/v1/items/1");
		assertThat(new String(reply.get().getBody(), StandardCharsets.UTF_8)).isEqualTo("created");
		HttpExchange exchange = lastExchange.get();
		assertThat(exchange.getRequestMethod()).isEqualTo("POST");
		assertThat(exchange.getRequestURI().toString()).isEqualTo("/ctx/v1/ok?a=1");
		assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer t");
		assertThat(exchange.getRequestHeaders().getFirst("X-Forwarded-For")).isEqualTo("203.0.113.7");
		assertThat(exchange.getRequestHeaders().getFirst("Host")).startsWith("127.0.0.1:");
		assertThat(lastBody.get()).isEqualTo("{\"a\":1}");
	}

	@Test
	void redirect_is_returned_not_followed() {
		StreamHttpReply reply = dispatcher.dispatch(request("GET", "/v1/redirect", null, 5_000), backendRoute())
			.orElseThrow();

		assertThat(reply.getStatus()).isEqualTo(302);
		assertThat(reply.header("location")).isEqualTo("/elsewhere");
	}

	@Test
	void backend_slower_than_deadline_gets_no_reply() {
		assertThat(dispatcher.dispatch(request("GET", "/v1/slow", null, 300), backendRoute())).isEmpty();
	}

	@Test
	void expired_request_is_not_dispatched() {
		int before = hits.get();

		assertThat(dispatcher.dispatch(request("GET", "/v1/ok", null, -1), backendRoute())).isEmpty();
		assertThat(hits.get()).isEqualTo(before);
	}

	@Test
	void too_large_response_is_502() {
		properties.getLocal().setMaxResponseBody(DataSize.ofKilobytes(1));

		StreamHttpReply reply = dispatcher.dispatch(request("GET", "/v1/big", null, 5_000), backendRoute())
			.orElseThrow();

		assertThat(reply.getStatus()).isEqualTo(502);
		assertThat(reply.header("x-umd-error")).isEqualTo(UmdErrorCode.RESPONSE_TOO_LARGE.getCode());
	}

	@Test
	void unreachable_backend_is_502() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		StreamHttpReply reply = dispatcher.dispatch(request("GET", "/v1/ok", null, 5_000),
			route(URI.create("http://127.0.0.1:" + closedPort))).orElseThrow();

		assertThat(reply.getStatus()).isEqualTo(502);
		assertThat(reply.header("x-umd-error")).isEqualTo(UmdErrorCode.LOCAL_BACKEND_UNAVAILABLE.getCode());
	}

	@Test
	void target_uri_keeps_path_and_appends_base_path() {
		assertThat(LocalHttpDispatcher.targetUri(URI.create("http://h:8080/"), "/v1/a%20b", null))
			.hasToString("http://h:8080/v1/a%20b");
		assertThat(LocalHttpDispatcher.targetUri(URI.create("http://h:8080/tsh"), "/v1/x", "q=1"))
			.hasToString("http://h:8080/tsh/v1/x?q=1");
	}
}
