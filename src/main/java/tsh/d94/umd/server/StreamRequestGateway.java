package tsh.d94.umd.server;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ProxyHeaderPolicy;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;

/**
 * UMD(server) -> UMD(local) HTTP 요청-응답 진입점.
 * 응답 Future 는 응답 수신 시 정상 완료, 타임아웃 시 TimeoutException, 발행 실패 시 해당 예외,
 * UMD 종료 시 StreamShutdownException 으로 완료된다.
 */
@Slf4j
@Service
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class StreamRequestGateway {

	private final RequestPublisher publisher;
	private final PendingReplyRegistry registry;
	private final UmdProperties properties;
	private final Clock clock;

	@Autowired
	public StreamRequestGateway(RequestPublisher publisher, PendingReplyRegistry registry,
		UmdProperties properties) {
		this(publisher, registry, properties, Clock.systemUTC());
	}

	StreamRequestGateway(RequestPublisher publisher, PendingReplyRegistry registry,
		UmdProperties properties, Clock clock) {
		this.publisher = publisher;
		this.registry = registry;
		this.properties = properties;
		this.clock = clock;
	}

	public StreamCall request(String method, String path, String query,
		Map<String, List<String>> headers, byte[] body) {
		return request(method, path, query, headers, body, properties.getServer().getRequestTimeout());
	}

	public StreamCall request(String method, String path, String query,
		Map<String, List<String>> headers, byte[] body, Duration timeout) {
		long now = clock.millis();
		String corrId = UUID.randomUUID().toString();
		// 백엔드도 같은 ID 로 로그를 남길 수 있도록 corrId 를 X-Trace-Id 로 함께 전달한다.
		Map<String, List<String>> outHeaders = new LinkedHashMap<>(headers == null ? Map.of() : headers);
		outHeaders.put(ProxyHeaderPolicy.X_TRACE_ID, List.of(corrId));
		StreamHttpRequest request = StreamHttpRequest.builder()
			.corrId(corrId)
			.replyTo(properties.getReplyStream())
			.method(method)
			.path(path)
			.query(query)
			.headers(outHeaders)
			.body(body)
			.ts(now)
			.deadline(now + timeout.toMillis())
			.build();

		// 발행 전에 등록해야 매우 빠른 응답도 놓치지 않는다.
		CompletableFuture<StreamHttpReply> future = registry.register(request.getCorrId(), timeout);
		try {
			publisher.publish(request);
		} catch (RuntimeException e) {
			log.warn("Stream 요청 발행 실패. corrId={} {} {} cause={}",
				request.getCorrId(), method, path, e.getMessage());
			registry.fail(request.getCorrId(), e);
		}
		return new StreamCall(request.getCorrId(), future);
	}
}
