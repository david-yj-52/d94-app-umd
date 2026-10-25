package tsh.d94.umd.server;

/**
 * UMD 종료로 응답 대기가 중단되었을 때 대기 중인 요청을 실패시키는 예외.
 */
public class StreamShutdownException extends IllegalStateException {

	public StreamShutdownException(String message) {
		super(message);
	}
}
