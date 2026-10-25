package tsh.d94.umd.local;

import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * [local] 응답을 요청의 replyTo Stream 에 XADD (MAXLEN ~1000).
 * server 가 꺼져 있는 동안에도 응답 Stream 이 무한히 커지지 않게 한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class ReplyPublisher {

	private static final XAddOptions REPLY_ADD_OPTIONS =
		XAddOptions.maxlen(StreamKeys.REPLY_STREAM_MAX_LEN).approximateTrimming(true);

	private final StringRedisTemplate redisTemplate;

	public void publish(String replyTo, StreamHttpReply reply) {
		redisTemplate.opsForStream().add(StreamRecords.newRecord().in(replyTo).ofMap(reply.toFields()),
			REPLY_ADD_OPTIONS);
	}
}
