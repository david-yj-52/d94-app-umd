package tsh.d94.umd.local;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.spec.StreamHttpRequest;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * 실제 Redis 에서 XPENDING + XCLAIM 회수 동작 검증 (소비 루프 없이 회수기만).
 */
@Testcontainers(disabledWithoutDocker = true)
class PendingReclaimerIntegrationTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	private LettuceConnectionFactory connectionFactory;
	private StringRedisTemplate redisTemplate;
	private final RequestStreamConsumer consumer = mock(RequestStreamConsumer.class);
	private final List<RecordId> submitted = new ArrayList<>();
	private PendingReclaimer reclaimer;

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(
			new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();
		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.delete(StreamKeys.REQUEST_STREAM);
		redisTemplate.opsForStream().createGroup(StreamKeys.REQUEST_STREAM, ReadOffset.from("0"), StreamKeys.CONSUMER_GROUP);

		UmdProperties properties = new UmdProperties();
		properties.setInstanceId("umd-home-new");
		properties.getLocal().setReclaimIdle(Duration.ofMillis(200));
		when(consumer.isRunning()).thenReturn(true);
		when(consumer.trySubmit(any(), any())).thenAnswer(inv -> submitted.add(inv.getArgument(0)));
		reclaimer = new PendingReclaimer(redisTemplate, properties, consumer, new LocalMetrics(new SimpleMeterRegistry()));
	}

	@AfterEach
	void tearDown() {
		connectionFactory.destroy();
	}

	/** 요청을 적재하고 다른(죽은) consumer 가 읽기만 한 상태 = Pending */
	private RecordId pendingByDeadConsumer() {
		long now = System.currentTimeMillis();
		RecordId id = redisTemplate.opsForStream().add(StreamRecords.newRecord().in(StreamKeys.REQUEST_STREAM)
			.ofMap(StreamHttpRequest.builder().corrId("c").replyTo("r").method("GET").path("/v1/x")
				.ts(now).deadline(now + 60_000).build().toFields()));
		redisTemplate.opsForStream().read(Consumer.from(StreamKeys.CONSUMER_GROUP, "umd-home-dead"),
			StreamReadOptions.empty().count(1), StreamOffset.create(StreamKeys.REQUEST_STREAM, ReadOffset.lastConsumed()));
		return id;
	}

	@Test
	void entries_idle_longer_than_threshold_are_claimed_and_submitted() throws Exception {
		RecordId id = pendingByDeadConsumer();
		Thread.sleep(300);

		assertThat(reclaimer.reclaimOnce()).isEqualTo(1);
		assertThat(submitted).containsExactly(id);
		// 소유자가 회수한 consumer 로 바뀜
		assertThat(redisTemplate.opsForStream().pending(StreamKeys.REQUEST_STREAM,
			Consumer.from(StreamKeys.CONSUMER_GROUP, "umd-home-new"), Range.unbounded(), 10L)).hasSize(1);
	}

	@Test
	void recently_delivered_entries_are_left_alone() {
		pendingByDeadConsumer();

		assertThat(reclaimer.reclaimOnce()).isZero();
		assertThat(submitted).isEmpty();
	}

	@Test
	void stops_when_consumer_has_no_capacity() throws Exception {
		pendingByDeadConsumer();
		pendingByDeadConsumer();
		Thread.sleep(300);
		when(consumer.trySubmit(any(), any())).thenReturn(false);

		assertThat(reclaimer.reclaimOnce()).isZero();
	}

	@Test
	void nothing_pending_is_noop() {
		assertThat(reclaimer.reclaimOnce()).isZero();
		verify(consumer, never()).trySubmit(any(), any(Map.class));
	}
}
