package tsh.d94.umd.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * UMD 가 HTTP 헤더를 Stream 으로 옮기거나 다시 HTTP 로 내보낼 때 적용하는 헤더 필터 (양 모드 공용).
 * 헤더명은 모두 소문자로 다룬다.
 *
 * <pre>
 * [server] UI 요청      → forRequest() → withForwarded() → Stream
 * [local]  Stream 요청  → forLocalDispatch()            → 로컬 백엔드 호출
 * [local]  백엔드 응답  → forResponse()                 → Stream
 * [server] Stream 응답  → forResponse()                 → UI 응답
 * </pre>
 */
public final class ProxyHeaderPolicy {

	public static final String X_FORWARDED_FOR = "x-forwarded-for";
	public static final String X_FORWARDED_PROTO = "x-forwarded-proto";
	public static final String X_FORWARDED_HOST = "x-forwarded-host";
	public static final String X_TRACE_ID = "x-trace-id";

	/** RFC 9110 hop-by-hop. 프록시가 다음 홉으로 넘기지 않는다. */
	private static final Set<String> HOP_BY_HOP = Set.of(
		"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
		"proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");

	/** UI 요청에서 버리는 헤더. Authorization 은 백엔드 검증을 위해 그대로 전달한다. */
	private static final Set<String> REQUEST_EXCLUDED = Set.of("host", "content-length", "cookie");

	/** server 가 직접 설정하는 헤더. UI 가 보낸 값은 버린다 (x-forwarded-for 는 기존 체인에 이어 붙임). */
	private static final Set<String> SERVER_RESERVED = Set.of(X_FORWARDED_PROTO, X_FORWARDED_HOST, X_TRACE_ID);

	private ProxyHeaderPolicy() {
	}

	/**
	 * [server] UI 요청 헤더 → Stream 에 실을 헤더.
	 */
	public static Map<String, List<String>> forRequest(Map<String, List<String>> incoming) {
		Map<String, List<String>> headers = withoutHopByHop(incoming);
		headers.keySet().removeIf(name -> REQUEST_EXCLUDED.contains(name) || SERVER_RESERVED.contains(name));
		return headers;
	}

	/**
	 * [server] 표준 X-Forwarded-* 와 Trace ID 를 설정한다.
	 * X-Forwarded-For 는 앞단(Next.js 서버 등)이 보낸 체인 뒤에 직전 홉 주소를 이어 붙인다 (표준 동작).
	 */
	public static Map<String, List<String>> withForwarded(Map<String, List<String>> headers,
		String remoteAddr, String proto, String host, String traceId) {
		Map<String, List<String>> result = new LinkedHashMap<>(headers);
		List<String> chain = new ArrayList<>();
		List<String> existing = result.get(X_FORWARDED_FOR);
		if (existing != null) {
			existing.stream().filter(v -> v != null && !v.isBlank()).forEach(chain::add);
		}
		if (remoteAddr != null && !remoteAddr.isBlank()) {
			chain.add(remoteAddr);
		}
		if (!chain.isEmpty()) {
			result.put(X_FORWARDED_FOR, List.of(String.join(", ", chain)));
		}
		putIfPresent(result, X_FORWARDED_PROTO, proto);
		putIfPresent(result, X_FORWARDED_HOST, host);
		putIfPresent(result, X_TRACE_ID, traceId);
		return result;
	}

	/**
	 * [local] Stream 요청 헤더 → 로컬 백엔드 호출 헤더. Host 는 호출 대상 기준으로 HTTP 클라이언트가 설정한다.
	 */
	public static Map<String, List<String>> forLocalDispatch(Map<String, List<String>> streamHeaders) {
		Map<String, List<String>> headers = withoutHopByHop(streamHeaders);
		headers.remove("host");
		headers.remove("content-length");
		return headers;
	}

	/**
	 * [local / server] 응답 헤더. Content-Length 는 본문을 내보내는 쪽이 다시 계산한다.
	 */
	public static Map<String, List<String>> forResponse(Map<String, List<String>> responseHeaders) {
		Map<String, List<String>> headers = withoutHopByHop(responseHeaders);
		headers.remove("content-length");
		return headers;
	}

	/**
	 * 소문자 정규화 + hop-by-hop 및 Connection 헤더에 나열된 헤더 제거.
	 */
	static Map<String, List<String>> withoutHopByHop(Map<String, List<String>> source) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		if (source == null) {
			return headers;
		}
		source.forEach((name, values) -> {
			if (name != null && values != null && !values.isEmpty()) {
				headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).addAll(values);
			}
		});
		List<String> connection = headers.getOrDefault("connection", List.of());
		connection.stream()
			.flatMap(v -> List.of(v.split(",")).stream())
			.map(v -> v.trim().toLowerCase(Locale.ROOT))
			.filter(v -> !v.isEmpty())
			.forEach(headers::remove);
		headers.keySet().removeIf(HOP_BY_HOP::contains);
		return headers;
	}

	private static void putIfPresent(Map<String, List<String>> headers, String name, String value) {
		if (value != null && !value.isBlank()) {
			headers.put(name, List.of(value));
		}
	}
}
