package tsh.d94.umd.local;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;
import tsh.d94.umd.common.spec.StreamKeys;

class LocalRequestProcessorTest {

	private static final RecordId ID = RecordId.of("1-0");

	private final LocalRouteResolver resolver = mock(LocalRouteResolver.class);
	private final LocalHttpDispatcher dispatcher = mock(LocalHttpDispatcher.class);
	private final ReplyPublisher publisher = mock(ReplyPublisher.class);
	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private LocalRequestProcessor processor;
	private final UmdProperties.Route route = new UmdProperties.Route();

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForStream()).thenReturn(streamOps);
		route.setId("default");
		processor = new LocalRequestProcessor(resolver, dispatcher, publisher, redisTemplate,
			new LocalMetrics(meterRegistry), Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC));
	}

	private Map<String, String> fields(long deadline) {
		return StreamHttpRequest.builder()
			.corrId("c1").replyTo("d94:umd:rep:s1").method("GET").path("/v1/items")
			.ts(1_000).deadline(deadline).build().toFields();
	}

	private void verifyAcked() {
		verify(streamOps).acknowledge(StreamKeys.REQUEST_STREAM, StreamKeys.CONSUMER_GROUP, ID);
		verify(streamOps).delete(StreamKeys.REQUEST_STREAM, ID);
	}

	@Test
	void dispatches_and_publishes_reply_then_acks_and_deletes() {
		StreamHttpReply reply = StreamHttpReply.builder().corrId("c1").status(200).build();
		when(resolver.resolve("/v1/items")).thenReturn(Optional.of(route));
		when(dispatcher.dispatch(any(), eq(route))).thenReturn(Optional.of(reply));

		processor.process(ID, fields(10_000));

		verify(publisher).publish("d94:umd:rep:s1", reply);
		verifyAcked();
	}

	@Test
	void expired_request_is_acked_without_dispatch() {
		processor.process(ID, fields(4_000));

		verifyNoInteractions(dispatcher, publisher);
		verifyAcked();
		assertThat(meterRegistry.counter("umd.local.expired").count()).isEqualTo(1);
	}

	@Test
	void malformed_request_is_acked_without_reply() {
		processor.process(ID, Map.of("corrId", "c1"));

		verifyNoInteractions(dispatcher, publisher);
		verifyAcked();
	}

	@Test
	void missing_route_replies_no_route() {
		StreamHttpReply noRoute = StreamHttpReply.builder().corrId("c1").status(404).build();
		when(resolver.resolve(any())).thenReturn(Optional.empty());
		when(dispatcher.error(any(), eq(UmdErrorCode.NO_ROUTE))).thenReturn(noRoute);

		processor.process(ID, fields(10_000));

		verify(publisher).publish("d94:umd:rep:s1", noRoute);
		verifyAcked();
	}

	@Test
	void no_reply_on_backend_timeout_but_still_acked() {
		when(resolver.resolve(any())).thenReturn(Optional.of(route));
		when(dispatcher.dispatch(any(), any())).thenReturn(Optional.empty());

		processor.process(ID, fields(10_000));

		verifyNoInteractions(publisher);
		verifyAcked();
	}

	@Test
	void publish_failure_leaves_entry_pending() {
		when(resolver.resolve(any())).thenReturn(Optional.of(route));
		when(dispatcher.dispatch(any(), any()))
			.thenReturn(Optional.of(StreamHttpReply.builder().corrId("c1").status(200).build()));
		doThrow(new RedisConnectionFailureException("down")).when(publisher).publish(any(), any());

		processor.process(ID, fields(10_000));

		verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
		verify(streamOps, never()).delete(anyString(), any(RecordId[].class));
	}
}
