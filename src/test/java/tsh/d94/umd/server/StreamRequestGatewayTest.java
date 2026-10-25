package tsh.d94.umd.server;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;
import tsh.d94.umd.common.UmdProperties;

class StreamRequestGatewayTest {

	private final RequestPublisher publisher = mock(RequestPublisher.class);
	private final PendingReplyRegistry registry = new PendingReplyRegistry();
	private final UmdProperties properties = new UmdProperties();
	private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC);

	private StreamRequestGateway gateway;

	@BeforeEach
	void setUp() {
		properties.setInstanceId("umd-1");
		gateway = new StreamRequestGateway(publisher, registry, properties, clock);
	}

	@Test
	void request_publishes_http_envelope_and_completes_on_reply() throws Exception {
		StreamCall call = gateway.request("POST", "/v1/items", "page=1",
			Map.of("content-type", List.of("application/json")),
			"{}".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10));

		ArgumentCaptor<StreamHttpRequest> captor = ArgumentCaptor.forClass(StreamHttpRequest.class);
		verify(publisher).publish(captor.capture());
		StreamHttpRequest sent = captor.getValue();
		assertThat(sent.getCorrId()).isEqualTo(call.corrId());
		assertThat(sent.getMethod()).isEqualTo("POST");
		assertThat(sent.getPath()).isEqualTo("/v1/items");
		assertThat(sent.getQuery()).isEqualTo("page=1");
		assertThat(sent.getReplyTo()).isEqualTo("d94:umd:rep:umd-1");
		assertThat(sent.getTs()).isEqualTo(1_000L);
		assertThat(sent.getDeadline()).isEqualTo(11_000L);

		registry.complete(sent.replyBuilder().status(201).build());

		assertThat(call.reply().get().getStatus()).isEqualTo(201);
	}

	@Test
	void publish_failure_fails_future_and_clears_registry() {
		doThrow(new RedisConnectionFailureException("down")).when(publisher).publish(any());

		CompletableFuture<StreamHttpReply> future =
			gateway.request("GET", "/v1/items", null, Map.of(), null).reply();

		assertThatThrownBy(future::get)
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(RedisConnectionFailureException.class);
		assertThat(registry.size()).isZero();
	}
}
