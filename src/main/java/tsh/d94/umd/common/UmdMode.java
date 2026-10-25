package tsh.d94.umd.common;

/**
 * UMD 실행 모드.
 * <ul>
 *   <li>SERVER: AP-01. UI HTTP 요청을 Redis Stream 으로 변환하고 응답을 복원한다.</li>
 *   <li>LOCAL: Home PC. Redis Stream 요청을 로컬 백엔드 HTTP 호출로 변환하고 응답을 발행한다.</li>
 *   <li>BOTH: 한 프로세스에서 둘 다 (로컬 개발·종단 테스트 전용, 운영 사용 금지).</li>
 * </ul>
 */
public enum UmdMode {
	SERVER,
	LOCAL,
	BOTH
}
