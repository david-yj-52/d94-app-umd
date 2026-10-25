package tsh.d94.umd.server.proxy;

import java.util.List;
import java.util.Map;

/**
 * 서블릿 요청에서 읽어낸 프록시 대상 요청 (servlet API 와 분리해 단위 테스트 가능하게 한다).
 *
 * @param path         요청 URI 그대로 (인코딩 유지, context-path 없음)
 * @param query        raw query string, 없으면 null
 * @param bodyTooLarge 본문이 max-request-body 를 넘었으면 true (이때 body 는 비어 있음)
 * @param remoteAddr   직전 홉 주소 (X-Forwarded-For 에 이어 붙임)
 */
public record ProxyRequest(
	String method,
	String path,
	String query,
	Map<String, List<String>> headers,
	byte[] body,
	boolean bodyTooLarge,
	String remoteAddr,
	String scheme,
	String host
) {
}
