package tsh.d94.umd.server.proxy;

import java.util.List;
import java.util.Map;

import tsh.d94.umd.common.ProxyHeaderPolicy;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.common.error.UmdErrorResponse;

/**
 * UI 로 내보낼 응답.
 *
 * @param traceId 요청 corrId. Stream 발행 전 거절된 경우 null
 * @param error   UMD(server) 가 직접 만든 응답이면 오류 코드, local 응답을 그대로 전달하면 null
 */
public record ProxyResponse(
	int status,
	Map<String, List<String>> headers,
	byte[] body,
	String traceId,
	UmdErrorCode error
) {

	/** local 응답 그대로 (hop-by-hop, Content-Length 제외) */
	public static ProxyResponse passthrough(int status, Map<String, List<String>> headers, byte[] body,
		String traceId) {
		return new ProxyResponse(status, ProxyHeaderPolicy.forResponse(headers), body, traceId, null);
	}

	/** UMD(server) 자체 오류 응답 (ApiResponse JSON + X-Umd-Error) */
	public static ProxyResponse error(UmdErrorCode code, String traceId) {
		return new ProxyResponse(code.getStatus().value(), UmdErrorResponse.headers(code),
			UmdErrorResponse.body(code), traceId, code);
	}

	public boolean isUmdError() {
		return error != null;
	}
}
