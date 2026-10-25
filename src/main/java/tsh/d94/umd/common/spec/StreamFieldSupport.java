package tsh.d94.umd.common.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stream 엔트리(Map) 변환 공통 유틸.
 */
final class StreamFieldSupport {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {
	};

	private StreamFieldSupport() {
	}

	static String required(Map<String, String> fields, String name) {
		String value = fields.get(name);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Stream 필드 누락: " + name);
		}
		return value;
	}

	static long requiredLong(Map<String, String> fields, String name) {
		String value = required(fields, name);
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Stream 필드 형식 오류: " + name + "=" + value, e);
		}
	}

	static int requiredInt(Map<String, String> fields, String name) {
		String value = required(fields, name);
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Stream 필드 형식 오류: " + name + "=" + value, e);
		}
	}

	static void putIfNotNull(Map<String, String> fields, String name, Object value) {
		if (value != null) {
			fields.put(name, value.toString());
		}
	}

	/**
	 * 헤더 이름은 소문자로 정규화한다 (HTTP 헤더는 대소문자 구분 없음).
	 */
	static Map<String, List<String>> normalizeHeaders(Map<String, List<String>> headers) {
		Map<String, List<String>> normalized = new LinkedHashMap<>();
		if (headers == null) {
			return normalized;
		}
		headers.forEach((name, values) -> {
			if (name != null && values != null && !values.isEmpty()) {
				normalized.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>())
					.addAll(values);
			}
		});
		return normalized;
	}

	static void putHeaders(Map<String, String> fields, Map<String, List<String>> headers) {
		if (headers == null || headers.isEmpty()) {
			return;
		}
		try {
			fields.put(StreamField.HEADERS, OBJECT_MAPPER.writeValueAsString(headers));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("headers 직렬화 실패", e);
		}
	}

	static Map<String, List<String>> readHeaders(Map<String, String> fields) {
		String value = fields.get(StreamField.HEADERS);
		if (value == null || value.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return normalizeHeaders(OBJECT_MAPPER.readValue(value, HEADERS_TYPE));
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Stream 필드 형식 오류: " + StreamField.HEADERS, e);
		}
	}

	static String firstHeader(Map<String, List<String>> headers, String name) {
		List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	static void putBody(Map<String, String> fields, byte[] body, Map<String, List<String>> headers) {
		StreamBodyCodec.Encoded encoded = StreamBodyCodec.encode(body, firstHeader(headers, "content-type"));
		if (encoded != null) {
			fields.put(StreamField.BODY, encoded.value());
			fields.put(StreamField.BODY_ENCODING, encoded.encoding());
		}
	}

	static byte[] readBody(Map<String, String> fields) {
		String body = fields.get(StreamField.BODY);
		if (body == null) {
			return new byte[0];
		}
		return StreamBodyCodec.decode(body, required(fields, StreamField.BODY_ENCODING));
	}
}
