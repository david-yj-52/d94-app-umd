package tsh.d94.umd.server.proxy;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tsh.d94.umd.common.ProxyHeaderPolicy;
import tsh.d94.umd.common.error.UmdErrorCode;

/**
 * ProxyResponse 를 서블릿 응답으로 쓴다.
 * CORS 는 백엔드 소유이므로 손대지 않는다. 단, UMD 자체 오류 응답에는 백엔드가 CORS 헤더를 붙일 기회가 없어
 * 브라우저가 상태를 읽지 못하므로, 요청 Origin 이 umd.server.cors-origins 에 있을 때만 최소 CORS 헤더를 붙인다.
 */
public final class ProxyResponseWriter {

	private static final String EXPOSED = "X-Trace-Id, " + UmdErrorCode.HEADER;

	private ProxyResponseWriter() {
	}

	public static void write(HttpServletRequest request, HttpServletResponse response, ProxyResponse proxyResponse,
		List<String> corsOrigins) throws IOException {
		response.setStatus(proxyResponse.status());
		proxyResponse.headers().forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
		if (proxyResponse.traceId() != null) {
			response.setHeader(ProxyHeaderPolicy.X_TRACE_ID, proxyResponse.traceId());
		}
		if (proxyResponse.isUmdError()) {
			applyErrorCors(request, response, corsOrigins);
		}
		byte[] body = proxyResponse.body() == null ? new byte[0] : proxyResponse.body();
		if ("HEAD".equalsIgnoreCase(request.getMethod()) || body.length == 0) {
			response.setContentLength(0);
			return;
		}
		response.setContentLength(body.length);
		response.getOutputStream().write(body);
		response.flushBuffer();
	}

	static void applyErrorCors(HttpServletRequest request, HttpServletResponse response, List<String> corsOrigins) {
		String origin = request.getHeader("Origin");
		if (origin == null || corsOrigins == null || corsOrigins.isEmpty()) {
			return;
		}
		if (corsOrigins.contains("*") || corsOrigins.contains(origin)) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Access-Control-Allow-Credentials", "true");
			response.setHeader("Access-Control-Expose-Headers", EXPOSED);
			response.addHeader("Vary", "Origin");
		}
	}
}
