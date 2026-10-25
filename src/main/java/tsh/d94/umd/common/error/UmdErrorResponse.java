package tsh.d94.umd.common.error;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import tsh.d94.lib.core.interfaces.rest.ApiResponse;

/**
 * UMD 가 직접 만드는 오류 응답의 헤더·본문 (server / local 공용).
 * 본문은 ApiResponse JSON, 헤더에 X-Umd-Error 로 코드를 남긴다.
 */
public final class UmdErrorResponse {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private UmdErrorResponse() {
	}

	public static Map<String, List<String>> headers(UmdErrorCode code) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("content-type", List.of("application/json"));
		headers.put(UmdErrorCode.HEADER.toLowerCase(Locale.ROOT), List.of(code.getCode()));
		return headers;
	}

	public static byte[] body(UmdErrorCode code) {
		try {
			return OBJECT_MAPPER.writeValueAsBytes(ApiResponse.error(code.getCode(), code.getMessage()));
		} catch (JsonProcessingException e) {
			return ("{\"success\":false,\"code\":\"" + code.getCode() + "\"}").getBytes(StandardCharsets.UTF_8);
		}
	}
}
