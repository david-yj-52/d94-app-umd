package tsh.d94.umd.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import tsh.d94.umd.common.spec.StreamKeys;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;

/**
 * 테스트용 UMD(local) 대역 Consumer.
 * 요청 Stream 을 XREADGROUP 으로 읽고, responder 결과를 replyTo Stream 에 XADD 한 뒤 XACK 한다.
 * responder 가 null 을 돌려주면 응답하지 않는다 (타임아웃 재현용).
 */
public class FakeLocalConsumer implements AutoCloseable {

	private final StringRedisTemplate redisTemplate;
	private final Function<StreamHttpRequest, StreamHttpReply> responder;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final AtomicBoolean running = new AtomicBoolean(true);

	public FakeLocalConsumer(StringRedisTemplate redisTemplate, Function<StreamHttpRequest, StreamHttpReply> responder) {
		this.redisTemplate = redisTemplate;
		this.responder = responder;
		executor.submit(this::loop);
	}

	@SuppressWarnings("unchecked")
	private void loop() {
		while (running.get()) {
			List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
				Consumer.from(StreamKeys.CONSUMER_GROUP, "fake-local"),
				StreamReadOptions.empty().block(Duration.ofMillis(200)),
				StreamOffset.create(StreamKeys.REQUEST_STREAM, ReadOffset.lastConsumed()));
			if (records == null) {
				continue;
			}
			for (MapRecord<String, Object, Object> record : records) {
				StreamHttpRequest request = StreamHttpRequest.fromFields((Map<String, String>)(Map<?, ?>)record.getValue());
				StreamHttpReply reply = responder.apply(request);
				if (reply != null) {
					redisTemplate.opsForStream().add(
						StreamRecords.newRecord().in(request.getReplyTo()).ofMap(reply.toFields()));
				}
				redisTemplate.opsForStream().acknowledge(StreamKeys.CONSUMER_GROUP, record);
			}
		}
	}

	@Override
	public void close() throws InterruptedException {
		running.set(false);
		executor.shutdown();
		executor.awaitTermination(5, TimeUnit.SECONDS);
	}
}
