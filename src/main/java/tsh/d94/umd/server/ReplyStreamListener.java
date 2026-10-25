package tsh.d94.umd.server;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.spec.StreamField;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;

/**
 * 자기 인스턴스의 응답 Stream(d94:umd:rep:{instanceId})을 읽어 대기 중인 요청을 완료시킨다.
 * <ul>
 *   <li>기동 시점 이후의 엔트리만 읽는다 (이전 실행의 잔여 응답 무시)</li>
 *   <li>처리한 엔트리는 XDEL 로 정리한다</li>
 *   <li>Web 서버 graceful shutdown 이후에 정지되도록 phase 를 낮게 둔다 (start 는 먼저, stop 은 나중)</li>
 *   <li>비정상 종료로 남은 응답 Stream 이 쌓이지 않도록 TTL 을 주기적으로 갱신한다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class ReplyStreamListener implements SmartLifecycle, StreamListener<String, MapRecord<String, String, String>> {

	private static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;
	private static final Duration READ_ERROR_BACKOFF = Duration.ofSeconds(2);
	private static final long READ_ERROR_LOG_INTERVAL = 30; // 약 1분마다
	/** 마지막 읽기 실패 후 이 시간 동안 추가 실패가 없으면 정상으로 본다 */
	private static final Duration READ_RECOVERY_WINDOW = Duration.ofSeconds(10);
	/** 살아있는 동안 10분마다 갱신 -> 비정상 종료 시 최대 1시간 후 자동 삭제 */
	static final Duration REPLY_STREAM_TTL = Duration.ofHours(1);

	private final AtomicLong consecutiveReadFailures = new AtomicLong();
	private volatile long lastReadErrorAt;

	private final RedisConnectionFactory connectionFactory;
	private final StringRedisTemplate redisTemplate;
	private final UmdProperties properties;
	private final PendingReplyRegistry registry;
	private final ServerStreamMetrics metrics;

	private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
	private volatile boolean running;

	@Override
	public void start() {
		String replyStream = properties.getReplyStream();
		container = StreamMessageListenerContainer.create(connectionFactory,
			StreamMessageListenerContainerOptions.builder()
				.pollTimeout(properties.getServer().getPollTimeout())
				.build());

		// ReadOffset.latest() 는 poll 사이에 들어온 엔트리를 놓칠 수 있어 기동 시각 기준 ID 부터 읽는다.
		ReadOffset startOffset = ReadOffset.from(System.currentTimeMillis() + "-0");
		container.register(
			StreamReadRequest.builder(StreamOffset.create(replyStream, startOffset))
				.cancelOnError(error -> false)
				.errorHandler(error -> onReadError(replyStream, error))
				.build(),
			this);
		container.start();
		running = true;
		log.info("Reply stream listener started. stream={}", replyStream);
	}

	@Override
	public void stop() {
		String replyStream = properties.getReplyStream();
		if (container != null) {
			container.stop();
		}
		registry.failAll(new StreamShutdownException("UMD 종료로 응답 대기를 중단합니다."));
		try {
			redisTemplate.delete(replyStream);
		} catch (RuntimeException e) {
			log.warn("Reply stream 삭제 실패. stream={} cause={}", replyStream, e.getMessage());
		}
		running = false;
		log.info("Reply stream listener stopped. stream={}", replyStream);
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}

	/**
	 * 최근 {@link #READ_RECOVERY_WINDOW} 안에 읽기 실패가 없으면 true.
	 * (Redis 장애 중에는 backoff 주기로 계속 실패하므로 false 가 유지된다)
	 */
	public boolean isReadHealthy() {
		return System.currentTimeMillis() - lastReadErrorAt > READ_RECOVERY_WINDOW.toMillis();
	}

	/**
	 * 응답 Stream 은 local 의 첫 XADD 로 생성되므로 키가 없으면 no-op 이고 다음 주기에 설정된다.
	 * XADD 는 TTL 을 지우지 않는다.
	 */
	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
	public void refreshReplyStreamTtl() {
		if (!running) {
			return;
		}
		try {
			redisTemplate.expire(properties.getReplyStream(), REPLY_STREAM_TTL);
		} catch (RuntimeException e) {
			log.debug("Reply stream TTL 갱신 실패. cause={}", e.getMessage());
		}
	}

	/**
	 * Redis 미연결 시 poll 이 즉시 실패하며 루프가 매우 빠르게 돌기 때문에
	 * polling 스레드에서 잠시 대기하고, 로그는 일정 횟수마다만 남긴다.
	 */
	private void onReadError(String replyStream, Throwable error) {
		long now = System.currentTimeMillis();
		if (now - lastReadErrorAt > READ_RECOVERY_WINDOW.toMillis()) {
			consecutiveReadFailures.set(0); // 회복 후 새로 시작된 장애
		}
		lastReadErrorAt = now;
		metrics.readError();

		long failures = consecutiveReadFailures.incrementAndGet();
		if (failures == 1 || failures % READ_ERROR_LOG_INTERVAL == 0) {
			log.warn("Reply stream read 실패 ({}회 연속). stream={} cause={}",
				failures, replyStream, error.getMessage());
		}
		try {
			Thread.sleep(READ_ERROR_BACKOFF.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void onMessage(MapRecord<String, String, String> record) {
		try {
			StreamHttpReply reply = StreamHttpReply.fromFields(record.getValue());
			if (registry.complete(reply)) {
				log.debug("Reply received. corrId={} status={}", reply.getCorrId(), reply.getStatus());
			} else {
				metrics.lateReply();
				log.warn("대기 중인 요청이 없는 응답 (타임아웃 후 도착 등). corrId={} status={}",
					reply.getCorrId(), reply.getStatus());
			}
		} catch (IllegalArgumentException e) {
			metrics.invalidReply();
			log.error("잘못된 응답 엔트리. id={} cause={}", record.getId(), e.getMessage());
			// corrId 를 읽을 수 있으면 타임아웃까지 기다리지 않고 즉시 502 로 끝낸다.
			String corrId = record.getValue().get(StreamField.CORR_ID);
			if (corrId != null && !corrId.isBlank()) {
				registry.fail(corrId, new StreamBadReplyException(e.getMessage()));
			}
		} finally {
			try {
				redisTemplate.opsForStream().delete(record.getStream(), record.getId());
			} catch (RuntimeException e) {
				log.warn("Reply XDEL 실패. id={} cause={}", record.getId(), e.getMessage());
			}
		}
	}
}
