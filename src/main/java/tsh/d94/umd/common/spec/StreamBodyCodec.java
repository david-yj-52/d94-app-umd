package tsh.d94.umd.common.spec;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * HTTP 본문 <-> Stream 필드 값 변환.
 * <ul>
 *   <li>utf8: 텍스트 계열 Content-Type 이면서 charset 이 없거나 UTF-8 인 경우 (Redis Insight 가독성)</li>
 *   <li>base64: 그 외 전부 (바이너리, 다른 charset, Content-Type 없음) — 바이트를 손실 없이 전달</li>
 * </ul>
 */
public final class StreamBodyCodec {

	public static final String UTF8 = "utf8";
	public static final String BASE64 = "base64";

	private static final Set<String> TEXT_APPLICATION_TYPES = Set.of(
		"application/json",
		"application/xml",
		"application/javascript",
		"application/x-www-form-urlencoded",
		"application/problem+json");

	private StreamBodyCodec() {
	}

	public record Encoded(String value, String encoding) {
	}

	/**
	 * @return 본문이 없으면 null
	 */
	public static Encoded encode(byte[] body, String contentType) {
		if (body == null || body.length == 0) {
			return null;
		}
		if (isUtf8Text(contentType)) {
			return new Encoded(new String(body, StandardCharsets.UTF_8), UTF8);
		}
		return new Encoded(Base64.getEncoder().encodeToString(body), BASE64);
	}

	/**
	 * @return 값이 없으면 빈 배열
	 */
	public static byte[] decode(String value, String encoding) {
		if (value == null || value.isEmpty()) {
			return new byte[0];
		}
		if (UTF8.equals(encoding)) {
			return value.getBytes(StandardCharsets.UTF_8);
		}
		if (BASE64.equals(encoding)) {
			try {
				return Base64.getDecoder().decode(value);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Stream 필드 형식 오류: body 가 base64 가 아닙니다.", e);
			}
		}
		throw new IllegalArgumentException("Stream 필드 값 오류: " + StreamField.BODY_ENCODING + "=" + encoding);
	}

	static boolean isUtf8Text(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return false;
		}
		String[] parts = contentType.toLowerCase(Locale.ROOT).split(";");
		String mediaType = parts[0].trim();
		boolean text = mediaType.startsWith("text/")
			|| TEXT_APPLICATION_TYPES.contains(mediaType)
			|| mediaType.endsWith("+json")
			|| mediaType.endsWith("+xml");
		if (!text) {
			return false;
		}
		for (int i = 1; i < parts.length; i++) {
			String param = parts[i].trim();
			if (param.startsWith("charset=")) {
				String charset = param.substring("charset=".length()).replace("\"", "").trim();
				return charset.equals("utf-8") || charset.equals("utf8");
			}
		}
		return true;
	}
}
