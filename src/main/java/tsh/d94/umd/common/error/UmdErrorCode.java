package tsh.d94.umd.common.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * UMD 가 직접 만드는 응답의 오류 코드 (ApiResponse.code, X-Umd-Error 헤더).
 * 로컬 백엔드가 돌려준 응답은 손대지 않으므로 여기에 해당하지 않는다.
 * UI·백엔드는 이 코드에 의존하지 않고 HTTP 상태코드로 처리한다 (진단용).
 */
@Getter
@RequiredArgsConstructor
public enum UmdErrorCode {

	// server
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "UMD_UNAUTHORIZED", "인증 토큰이 없거나 유효하지 않습니다."),
	PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "UMD_PAYLOAD_TOO_LARGE", "요청 본문이 허용 크기를 초과했습니다."),
	OVERLOADED(HttpStatus.SERVICE_UNAVAILABLE, "UMD_OVERLOADED", "처리 대기 중인 요청이 많습니다. 잠시 후 다시 시도하세요."),
	LOCAL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "UMD_LOCAL_UNAVAILABLE", "로컬 처리 서버가 연결되어 있지 않습니다."),
	REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "UMD_REDIS_UNAVAILABLE", "메시지 중계 서버에 연결할 수 없습니다."),
	SHUTTING_DOWN(HttpStatus.SERVICE_UNAVAILABLE, "UMD_SHUTTING_DOWN", "UMD 가 종료 중입니다. 잠시 후 다시 시도하세요."),
	TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "UMD_TIMEOUT", "응답 대기 시간이 초과되었습니다."),
	BAD_REPLY(HttpStatus.BAD_GATEWAY, "UMD_BAD_REPLY", "로컬 처리 서버의 응답 형식이 올바르지 않습니다."),

	// local
	NO_ROUTE(HttpStatus.NOT_FOUND, "UMD_NO_ROUTE", "요청 경로에 해당하는 로컬 서비스가 없습니다."),
	LOCAL_BACKEND_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "UMD_LOCAL_BACKEND_UNAVAILABLE", "로컬 백엔드에 연결할 수 없습니다."),
	RESPONSE_TOO_LARGE(HttpStatus.BAD_GATEWAY, "UMD_RESPONSE_TOO_LARGE", "응답 본문이 허용 크기를 초과했습니다."),

	INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "UMD_INTERNAL", "UMD 내부 오류가 발생했습니다.");

	public static final String HEADER = "X-Umd-Error";

	private final HttpStatus status;
	private final String code;
	private final String message;
}
