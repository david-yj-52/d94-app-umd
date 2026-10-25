package tsh.d94.umd.common.spec;

import static tsh.d94.umd.common.spec.StreamFieldSupport.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UMD(server) -> UMD(local) 로 전달되는 HTTP 요청 ({@link StreamKeys#REQUEST_STREAM}).
 * <ul>
 *   <li>corrId: 요청-응답 상관 ID (UI 응답의 X-Trace-Id)</li>
 *   <li>replyTo: 응답을 기록할 Stream 키 ({@link StreamKeys#replyStream(String)})</li>
 *   <li>method / path / query / headers / body: UI 가 보낸 HTTP 요청 그대로 (path 는 변환하지 않는다)</li>
 *   <li>deadline: epoch millis. 경과한 요청은 local 이 실행하지 않고 응답 없이 XACK 한다.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamHttpRequest {

	private String corrId;
	private String replyTo;
	private String method;
	private String path;
	/** raw query string ('?' 제외), 없으면 null */
	private String query;
	/** 소문자 헤더명 -> 값 목록 */
	@Builder.Default
	private Map<String, List<String>> headers = new LinkedHashMap<>();
	@Builder.Default
	private byte[] body = new byte[0];
	private long ts;
	private long deadline;

	public static StreamHttpRequest fromFields(Map<String, String> fields) {
		String path = required(fields, StreamField.PATH);
		if (!path.startsWith("/")) {
			throw new IllegalArgumentException("Stream 필드 값 오류: " + StreamField.PATH + "=" + path);
		}
		return StreamHttpRequest.builder()
			.corrId(required(fields, StreamField.CORR_ID))
			.replyTo(required(fields, StreamField.REPLY_TO))
			.method(required(fields, StreamField.METHOD).toUpperCase(Locale.ROOT))
			.path(path)
			.query(fields.get(StreamField.QUERY))
			.headers(readHeaders(fields))
			.body(readBody(fields))
			.ts(requiredLong(fields, StreamField.TS))
			.deadline(requiredLong(fields, StreamField.DEADLINE))
			.build();
	}

	public Map<String, String> toFields() {
		Map<String, List<String>> normalizedHeaders = normalizeHeaders(headers);
		Map<String, String> fields = new LinkedHashMap<>();
		putIfNotNull(fields, StreamField.CORR_ID, corrId);
		putIfNotNull(fields, StreamField.REPLY_TO, replyTo);
		putIfNotNull(fields, StreamField.METHOD, method == null ? null : method.toUpperCase(Locale.ROOT));
		putIfNotNull(fields, StreamField.PATH, path);
		if (query != null && !query.isEmpty()) {
			fields.put(StreamField.QUERY, query);
		}
		putHeaders(fields, normalizedHeaders);
		putBody(fields, body, normalizedHeaders);
		fields.put(StreamField.TS, Long.toString(ts));
		fields.put(StreamField.DEADLINE, Long.toString(deadline));
		return fields;
	}

	public boolean isExpired(long nowMillis) {
		return deadline > 0 && nowMillis > deadline;
	}

	public String header(String name) {
		return firstHeader(normalizeHeaders(headers), name);
	}

	/**
	 * 이 요청에 대한 응답 골격 (corrId 복사).
	 */
	public StreamHttpReply.StreamHttpReplyBuilder replyBuilder() {
		return StreamHttpReply.builder().corrId(corrId);
	}
}
