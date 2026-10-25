package tsh.d94.umd.local;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * [local] Pending 회수: 처리 중 local 이 죽어 ACK 되지 못한 엔트리를 다시 가져온다 (XPENDING + XCLAIM).
 * 회수한 엔트리도 {@link LocalRequestProcessor} 가 deadline 을 먼저 확인하므로,
 * 대부분(이미 만료) 백엔드를 다시 호출하지 않고 ACK 만 된다 → 타임아웃 이후 POST 중복 실행 방지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class PendingReclaimer {

	private static final int BATCH = 50;

	private final StringRedisTemplate redisTemplate;
	private final UmdProperties properties;
	private final RequestStreamConsumer consumer;
	private final LocalMetrics metrics;

	@Scheduled(initialDelayString = "${umd.local.reclaim-interval:30s}",
		fixedDelayString = "${umd.local.reclaim-interval:30s}")
	public void reclaim() {
		if (!consumer.isRunning()) {
			return;
		}
		try {
			reclaimOnce();
		} catch (RuntimeException e) {
			log.debug("Pending 회수 실패. cause={}", e.getMessage());
		}
	}

	@SuppressWarnings("unchecked")
	int reclaimOnce() {
		PendingMessages pending = redisTemplate.opsForStream()
			.pending(StreamKeys.REQUEST_STREAM, StreamKeys.CONSUMER_GROUP, Range.unbounded(), BATCH);
		if (pending == null || pending.isEmpty()) {
			return 0;
		}
		RecordId[] stale = pending.stream()
			.filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(properties.getLocal().getReclaimIdle()) >= 0)
			.map(PendingMessage::getId)
			.toArray(RecordId[]::new);
		if (stale.length == 0) {
			return 0;
		}
		List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
			StreamKeys.REQUEST_STREAM, StreamKeys.CONSUMER_GROUP, properties.getInstanceId(),
			properties.getLocal().getReclaimIdle(), stale);
		int submitted = 0;
		for (MapRecord<String, Object, Object> record : claimed) {
			if (!consumer.trySubmit(record.getId(), (Map<String, String>)(Map<?, ?>)record.getValue())) {
				break; // 처리 여유 없음 → 다음 주기에 다시 회수
			}
			submitted++;
		}
		if (submitted > 0) {
			metrics.reclaimed(submitted);
			log.info("Pending 엔트리 {}건 회수", submitted);
		}
		return submitted;
	}
}
