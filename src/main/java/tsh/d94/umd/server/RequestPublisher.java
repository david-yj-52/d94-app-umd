package tsh.d94.umd.server;

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.spec.StreamHttpRequest;
import tsh.d94.umd.common.spec.StreamKeys;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;

/**
 * 요청 Stream 발행 (XADD MAXLEN ~).
 * Consumer Group 생성은 양 모드 공통인 StreamGroupInitializer 가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class RequestPublisher {

	private static final XAddOptions REQUEST_ADD_OPTIONS =
		XAddOptions.maxlen(StreamKeys.REQUEST_STREAM_MAX_LEN).approximateTrimming(true);

	private final StringRedisTemplate redisTemplate;

	public RecordId publish(StreamHttpRequest request) {
		MapRecord<String, String, String> record = StreamRecords.newRecord()
			.in(StreamKeys.REQUEST_STREAM)
			.ofMap(request.toFields());
		RecordId recordId = redisTemplate.opsForStream().add(record, REQUEST_ADD_OPTIONS);
		log.debug("XADD {} id={} corrId={} {} {}",
			StreamKeys.REQUEST_STREAM, recordId, request.getCorrId(), request.getMethod(), request.getPath());
		return recordId;
	}
}
