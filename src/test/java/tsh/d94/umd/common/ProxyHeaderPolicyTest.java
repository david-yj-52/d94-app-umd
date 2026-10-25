package tsh.d94.umd.common;

import static org.assertj.core.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProxyHeaderPolicyTest {

	private Map<String, List<String>> uiRequest() {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Authorization", List.of("Bearer abc"));
		headers.put("Content-Type", List.of("application/json"));
		headers.put("Accept-Language", List.of("ko"));
		headers.put("Host", List.of("ap01.example.com"));
		headers.put("Content-Length", List.of("12"));
		headers.put("Cookie", List.of("session=1"));
		headers.put("Connection", List.of("keep-alive, X-Secret-Hop"));
		headers.put("X-Secret-Hop", List.of("drop-me"));
		headers.put("Transfer-Encoding", List.of("chunked"));
		headers.put("X-Forwarded-Proto", List.of("spoofed"));
		headers.put("X-Trace-Id", List.of("spoofed"));
		headers.put("X-Custom", List.of("keep"));
		return headers;
	}

	@Test
	void forRequest_keeps_authorization_and_app_headers_lowercased() {
		Map<String, List<String>> headers = ProxyHeaderPolicy.forRequest(uiRequest());

		assertThat(headers)
			.containsEntry("authorization", List.of("Bearer abc"))
			.containsEntry("content-type", List.of("application/json"))
			.containsEntry("accept-language", List.of("ko"))
			.containsEntry("x-custom", List.of("keep"));
	}

	@Test
	void forRequest_drops_hop_by_hop_host_length_cookie_and_reserved() {
		Map<String, List<String>> headers = ProxyHeaderPolicy.forRequest(uiRequest());

		assertThat(headers).doesNotContainKeys(
			"host", "content-length", "cookie", "connection", "transfer-encoding",
			"x-secret-hop",                       // Connection 헤더에 나열된 헤더
			"x-forwarded-proto", "x-trace-id");   // server 가 설정하는 예약 헤더
	}

	@Test
	void withForwarded_appends_remote_addr_to_existing_chain() {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("x-forwarded-for", List.of("203.0.113.7"));   // Next.js 서버가 넣은 브라우저 IP

		Map<String, List<String>> result = ProxyHeaderPolicy.withForwarded(
			headers, "10.0.0.5", "https", "ap01.example.com", "trace-1");

		assertThat(result)
			.containsEntry("x-forwarded-for", List.of("203.0.113.7, 10.0.0.5"))
			.containsEntry("x-forwarded-proto", List.of("https"))
			.containsEntry("x-forwarded-host", List.of("ap01.example.com"))
			.containsEntry("x-trace-id", List.of("trace-1"));
	}

	@Test
	void withForwarded_without_existing_chain_uses_remote_addr() {
		Map<String, List<String>> result = ProxyHeaderPolicy.withForwarded(
			new LinkedHashMap<>(), "10.0.0.5", "http", null, "trace-1");

		assertThat(result).containsEntry("x-forwarded-for", List.of("10.0.0.5"))
			.doesNotContainKey("x-forwarded-host");
	}

	@Test
	void forLocalDispatch_drops_host_and_length_keeps_auth_and_forwarded() {
		Map<String, List<String>> stream = new LinkedHashMap<>();
		stream.put("authorization", List.of("Bearer abc"));
		stream.put("x-forwarded-for", List.of("203.0.113.7"));
		stream.put("host", List.of("ap01.example.com"));
		stream.put("content-length", List.of("10"));

		assertThat(ProxyHeaderPolicy.forLocalDispatch(stream))
			.containsOnlyKeys("authorization", "x-forwarded-for");
	}

	@Test
	void forResponse_keeps_location_etag_and_drops_hop_by_hop() {
		Map<String, List<String>> response = new LinkedHashMap<>();
		response.put("Location", List.of("/v1/items/43"));
		response.put("ETag", List.of("\"v1\""));
		response.put("Set-Cookie", List.of("a=b"));
		response.put("Transfer-Encoding", List.of("chunked"));
		response.put("Content-Length", List.of("10"));
		response.put("Keep-Alive", List.of("timeout=5"));

		assertThat(ProxyHeaderPolicy.forResponse(response))
			.containsOnlyKeys("location", "etag", "set-cookie");
	}

	@Test
	void null_and_empty_values_are_ignored() {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("x-empty", List.of());
		headers.put(null, List.of("x"));

		assertThat(ProxyHeaderPolicy.forRequest(headers)).isEmpty();
		assertThat(ProxyHeaderPolicy.forRequest(null)).isEmpty();
	}
}
