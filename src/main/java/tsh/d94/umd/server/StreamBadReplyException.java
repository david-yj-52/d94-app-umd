package tsh.d94.umd.server;

/**
 * 응답 엔트리 형식이 잘못되었지만 corrId 는 읽을 수 있어 대기 중인 요청을 즉시 실패시킬 때 사용한다 (502).
 */
public class StreamBadReplyException extends RuntimeException {

	public StreamBadReplyException(String message) {
		super(message);
	}
}
