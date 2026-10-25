package tsh.d94.umd.server.proxy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.server.LocalAliveChecker;
import tsh.d94.umd.server.PendingReplyRegistry;
import tsh.d94.umd.server.ServerStreamMetrics;
import tsh.d94.umd.server.StreamBadReplyException;
import tsh.d94.umd.server.StreamCall;
import tsh.d94.umd.server.StreamRequestGateway;
import tsh.d94.umd.server.StreamShutdownException;

class StreamProxyServiceTest {

	private final StreamRequestGateway gateway = mock(StreamRequestGateway.class);
	private final PendingReplyRegistry registry = new PendingReplyRegistry();
	private final LocalAliveChecker aliveChecker = mock(LocalAliveChecker.class);
	private final UmdProperties properties = new UmdProperties();
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private StreamProxyService service;
	private CompletableFuture<StreamHttpReply> reply;

	@BeforeEach
	void setUp() {
		properties.setInstanceId("umd-1");
		service = new StreamProxyService(gateway, registry, aliveChecker, properties,
			new ServerStreamMetrics(meterRegistry, registry));
		reply = new CompletableFuture<>();
		when(aliveChecker.isAlive()).thenReturn(true);
		when(gateway.request(any(), any(), any(), any(), any())).thenReturn(new StreamCall("corr-1", reply));
	}

	private ProxyRequest request(boolean tooLarge) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Authorization", List.of("Bearer t"));
		headers.put("Cookie", List.of("s=1"));
		headers.put("X-Forwarded-For", List.of("203.0.113.7"));
		return new ProxyRequest("POST", "/v1/items", "a=1", headers,
			"{}".getBytes(StandardCharsets.UTF_8), tooLarge, "10.0.0.5", "https", "ap01.example.com");
	}

	private ProxyResponse proxy() throws Exception {
		return service.proxy(request(false)).get();
	}

	@Test
	void forwards_request_with_filtered_and_forwarded_headers() {
		service.proxy(request(false));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, List<String>>> headers = ArgumentCaptor.forClass(Map.class);
		verify(gateway).request(eq("POST"), eq("/v1/items"), eq("a=1"), headers.capture(), any());
		assertThat(headers.getValue())
			.containsEntry("authorization", List.of("Bearer t"))
			.containsEntry("x-forwarded-for", List.of("203.0.113.7, 10.0.0.5"))
			.containsEntry("x-forwarded-proto", List.of("https"))
			.containsEntry("x-forwarded-host", List.of("ap01.example.com"))
			.doesNotContainKey("cookie");
	}

	@Test
	void local_reply_is_passed_through_unchanged() throws Exception {
		reply.complete(StreamHttpReply.builder()
			.corrId("corr-1")
			.status(404)
			.headers(Map.of("content-type", List.of("application/json"), "transfer-encoding", List.of("chunked")))
			.body("{\"code\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8))
			.build());

		ProxyResponse res = proxy();

		assertThat(res.status()).isEqualTo(404);
		assertThat(res.isUmdError()).isFalse();
		assertThat(res.traceId()).isEqualTo("corr-1");
		assertThat(res.headers()).containsKey("content-type").doesNotContainKey("transfer-encoding");
		assertThat(new String(res.body(), StandardCharsets.UTF_8)).isEqualTo("{\"code\":\"NOT_FOUND\"}");
		assertThat(timerCount("POST", "4xx", "OK")).isEqualTo(1);
	}

	@Test
	void too_large_body_is_rejected_before_publish() throws Exception {
		ProxyResponse res = service.proxy(request(true)).get();

		assertError(res, UmdErrorCode.PAYLOAD_TOO_LARGE);
		assertThat(res.traceId()).isNull();
		verifyNoInteractions(gateway);
		assertThat(rejectedCount(UmdErrorCode.PAYLOAD_TOO_LARGE)).isEqualTo(1);
	}

	@Test
	void pending_limit_rejects_with_overloaded() throws Exception {
		properties.getServer().setMaxPending(1);
		registry.register("other", Duration.ofSeconds(5));

		assertError(proxy(), UmdErrorCode.OVERLOADED);
		verifyNoInteractions(gateway);
	}

	@Test
	void missing_local_heartbeat_rejects_immediately() throws Exception {
		when(aliveChecker.isAlive()).thenReturn(false);

		assertError(proxy(), UmdErrorCode.LOCAL_UNAVAILABLE);
		verifyNoInteractions(gateway);
	}

	@Test
	void heartbeat_check_can_be_disabled() throws Exception {
		properties.getServer().setRequireLocalAlive(false);
		when(aliveChecker.isAlive()).thenReturn(false);
		reply.complete(StreamHttpReply.builder().corrId("corr-1").status(200).build());

		assertThat(proxy().status()).isEqualTo(200);
		verify(aliveChecker, never()).isAlive();
	}

	@Test
	void redis_error_during_heartbeat_check_is_redis_unavailable() throws Exception {
		when(aliveChecker.isAlive()).thenThrow(new RedisConnectionFailureException("down"));

		assertError(proxy(), UmdErrorCode.REDIS_UNAVAILABLE);
	}

	@Test
	void timeout_is_504_with_trace_id() throws Exception {
		reply.completeExceptionally(new TimeoutException());

		ProxyResponse res = proxy();

		assertError(res, UmdErrorCode.TIMEOUT);
		assertThat(res.traceId()).isEqualTo("corr-1");
		assertThat(timerCount("POST", "5xx", "TIMEOUT")).isEqualTo(1);
	}

	@Test
	void stream_failures_are_classified() {
		assertThat(StreamProxyService.classify(new StreamShutdownException("x"))).isEqualTo(UmdErrorCode.SHUTTING_DOWN);
		assertThat(StreamProxyService.classify(new StreamBadReplyException("x"))).isEqualTo(UmdErrorCode.BAD_REPLY);
		assertThat(StreamProxyService.classify(new RedisConnectionFailureException("x")))
			.isEqualTo(UmdErrorCode.REDIS_UNAVAILABLE);
		assertThat(StreamProxyService.classify(new IllegalStateException("x"))).isEqualTo(UmdErrorCode.INTERNAL);
	}

	@Test
	void umd_error_body_is_api_response_json_with_header() throws Exception {
		reply.completeExceptionally(new StreamBadReplyException("bad"));

		ProxyResponse res = proxy();

		JsonNode body = objectMapper.readTree(res.body());
		assertThat(res.status()).isEqualTo(502);
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("code").asText()).isEqualTo("UMD_BAD_REPLY");
		assertThat(res.headers()).containsEntry("x-umd-error", List.of("UMD_BAD_REPLY"));
	}

	private void assertError(ProxyResponse res, UmdErrorCode code) {
		assertThat(res.status()).isEqualTo(code.getStatus().value());
		assertThat(res.error()).isEqualTo(code);
	}

	private long timerCount(String method, String status, String outcome) {
		Timer timer = meterRegistry.find("umd.proxy.request")
			.tag("method", method).tag("status", status).tag("outcome", outcome).timer();
		return timer == null ? 0 : timer.count();
	}

	private double rejectedCount(UmdErrorCode reason) {
		Counter counter = meterRegistry.find("umd.proxy.rejected").tag("reason", reason.name()).counter();
		return counter == null ? 0 : counter.count();
	}
}
