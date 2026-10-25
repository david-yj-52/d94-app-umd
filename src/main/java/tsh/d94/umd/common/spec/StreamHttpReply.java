package tsh.d94.umd.common.spec;

import static tsh.d94.umd.common.spec.StreamFieldSupport.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * UMD(local) -> UMD(server) 로 전달되는 HTTP 응답 (요청의 replyTo Stream).
 * 로컬 백엔드의 상태코드·헤더·본문을 그대로 담는다. 오류도 HTTP 상태코드로 표현한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamHttpReply {

	private String corrId;
	private int status;
	/** 소문자 헤더명 -> 값 목록 */
	@Builder.Default
	private Map<String, List<String>> headers = new LinkedHashMap<>();
	@Builder.Default
	private byte[] body = new byte[0];
	private long ts;

	public static StreamHttpReply fromFields(Map<String, String> fields) {
		int status = requiredInt(fields, StreamField.STATUS);
		if (status < 100 || status > 599) {
			throw new IllegalArgumentException("Stream 필드 값 오류: " + StreamField.STATUS + "=" + status);
		}
		return StreamHttpReply.builder()
			.corrId(required(fields, StreamField.CORR_ID))
			.status(status)
			.headers(readHeaders(fields))
			.body(readBody(fields))
			.ts(requiredLong(fields, StreamField.TS))
			.build();
	}

	public Map<String, String> toFields() {
		Map<String, List<String>> normalizedHeaders = normalizeHeaders(headers);
		Map<String, String> fields = new LinkedHashMap<>();
		putIfNotNull(fields, StreamField.CORR_ID, corrId);
		fields.put(StreamField.STATUS, Integer.toString(status));
		putHeaders(fields, normalizedHeaders);
		putBody(fields, body, normalizedHeaders);
		fields.put(StreamField.TS, Long.toString(ts));
		return fields;
	}

	public String header(String name) {
		return firstHeader(normalizeHeaders(headers), name);
	}
}
