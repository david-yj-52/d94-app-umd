package tsh.d94.umd.local;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.StreamGroupInitializer;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * [local] 요청 Stream 소비 루프.
 * <ul>
 *   <li>XREADGROUP home-backend {instanceId} — 처리 여유(세마포어 허가 수)만큼만 읽어 백엔드 과부하를 막는다.</li>
 *   <li>엔트리마다 가상 스레드에서 {@link LocalRequestProcessor} 로 처리.</li>
 *   <li>Redis 장애 시 2초 backoff, 로그는 약 1분 간격. NOGROUP 이면 Consumer Group 재생성.</li>
 *   <li>종료 시 읽기를 멈추고 처리 중인 요청이 끝나기를 기다린다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class RequestStreamConsumer implements SmartLifecycle {

	private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;
	private static final Duration READ_ERROR_BACKOFF = Duration.ofSeconds(2);
	private static final long READ_ERROR_LOG_INTERVAL = 30;
	private static final Duration READ_RECOVERY_WINDOW = Duration.ofSeconds(10);
	private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(25);

	private final StringRedisTemplate redisTemplate;
	private final UmdProperties properties;
	private final LocalRequestProcessor processor;
	private final StreamGroupInitializer groupInitializer;
	private final LocalMetrics metrics;
	private final Semaphore permits;

	private final AtomicLong consecutiveReadFailures = new AtomicLong();
	private volatile long lastReadErrorAt;
	private volatile boolean running;
	private ExecutorService workers;
	private Thread loopThread;

	public RequestStreamConsumer(StringRedisTemplate redisTemplate, UmdProperties properties,
		LocalRequestProcessor processor, StreamGroupInitializer groupInitializer, LocalMetrics metrics) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.processor = processor;
		this.groupInitializer = groupInitializer;
		this.metrics = metrics;
		this.permits = new Semaphore(properties.getLocal().getConsumerConcurrency());
		metrics.registerInflight(() -> properties.getLocal().getConsumerConcurrency() - permits.availablePermits());
	}

	@Override
	public void start() {
		// ApplicationReadyEvent(StreamGroupInitializer) 보다 먼저 시작하므로, 첫 XREADGROUP 이 NOGROUP 으로
		// 실패하지 않도록 여기서도 그룹을 보장한다 (멱등).
		groupInitializer.ensureConsumerGroup();
		workers = Executors.newVirtualThreadPerTaskExecutor();
		running = true;
		loopThread = Thread.ofPlatform().name("umd-local-consumer").daemon(true).start(this::loop);
		log.info("Local consumer started. stream={} group={} consumer={} concurrency={}",
			StreamKeys.REQUEST_STREAM, StreamKeys.CONSUMER_GROUP, properties.getInstanceId(),
			properties.getLocal().getConsumerConcurrency());
	}

	@Override
	public void stop() {
		running = false;
		if (loopThread != null) {
			try {
				loopThread.join(properties.getLocal().getPollTimeout().plusSeconds(1).toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (workers != null) {
			workers.shutdown();
			try {
				if (!workers.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
					log.warn("처리 중인 요청이 남은 채 종료합니다 (Pending 에 남아 다음 기동 시 회수).");
					workers.shutdownNow();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		log.info("Local consumer stopped.");
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}

	/** 최근 10초 안에 읽기 실패가 없으면 true */
	public boolean isReadHealthy() {
		return System.currentTimeMillis() - lastReadErrorAt > READ_RECOVERY_WINDOW.toMillis();
	}

	/**
	 * 처리 여유가 있으면 비동기로 처리를 시작한다 (회수한 엔트리용).
	 *
	 * @return 여유가 없어 시작하지 못했으면 false
	 */
	boolean trySubmit(RecordId id, Map<String, String> fields) {
		if (!running || !permits.tryAcquire()) {
			return false;
		}
		submit(id, fields);
		return true;
	}

	private void loop() {
		while (running) {
			try {
				// 최소 1건은 처리할 여유가 생길 때까지 대기
				if (!permits.tryAcquire(properties.getLocal().getPollTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
					continue;
				}
				int capacity = 1 + permits.drainPermits();
				int used = readAndSubmit(capacity);
				permits.release(capacity - used);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				onReadError(e);
			}
		}
	}

	/**
	 * 허가를 이미 확보한 상태에서 호출. 제출한 건수(사용한 허가 수)를 돌려준다.
	 */
	@SuppressWarnings("unchecked")
	private int readAndSubmit(int capacity) {
		List<MapRecord<String, Object, Object>> records;
		try {
			records = redisTemplate.opsForStream().read(
				Consumer.from(StreamKeys.CONSUMER_GROUP, properties.getInstanceId()),
				StreamReadOptions.empty().count(capacity).block(properties.getLocal().getPollTimeout()),
				StreamOffset.create(StreamKeys.REQUEST_STREAM, ReadOffset.lastConsumed()));
		} catch (RuntimeException e) {
			permits.release(capacity);
			throw e;
		}
		if (records == null || records.isEmpty()) {
			return 0;
		}
		for (MapRecord<String, Object, Object> record : records) {
			submit(record.getId(), (Map<String, String>)(Map<?, ?>)record.getValue());
		}
		return records.size();
	}

	private void submit(RecordId id, Map<String, String> fields) {
		workers.submit(() -> {
			try {
				processor.process(id, fields);
			} catch (RuntimeException e) {
				log.error("요청 처리 중 예외. id={}", id, e);
			} finally {
				permits.release();
			}
		});
	}

	private void onReadError(RuntimeException error) {
		// readAndSubmit 실패 시 허가는 이미 반납했다
		long now = System.currentTimeMillis();
		if (now - lastReadErrorAt > READ_RECOVERY_WINDOW.toMillis()) {
			consecutiveReadFailures.set(0);
		}
		lastReadErrorAt = now;
		metrics.readError();

		String message = StreamGroupInitializer.rootMessage(error);
		if (message.contains("NOGROUP")) {
			log.warn("Consumer group 이 없어 다시 생성합니다.");
			groupInitializer.ensureConsumerGroup();
		}
		long failures = consecutiveReadFailures.incrementAndGet();
		if (failures == 1 || failures % READ_ERROR_LOG_INTERVAL == 0) {
			log.warn("요청 Stream 읽기 실패 ({}회 연속). cause={}", failures, message);
		}
		try {
			Thread.sleep(READ_ERROR_BACKOFF.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
