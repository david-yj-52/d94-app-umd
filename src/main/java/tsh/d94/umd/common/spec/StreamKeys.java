package tsh.d94.umd.common.spec;

/**
 * UMD(server) <-> UMD(local) 간 Redis 키 / Consumer Group 이름.
 *
 * <pre>
 * server --XADD-->  d94:umd:req              --XREADGROUP(home-backend)/XACK-->  local
 * server <--XREAD-- d94:umd:rep:{instanceId} <--XADD--                           local
 * local  --SET EX-> d94:umd:local:alive      (server 가 Local 생존 확인)
 * </pre>
 * 응답 Stream 은 server 인스턴스별로 분리하여, server 가 여러 대일 때도 응답이 섞이지 않도록 한다.
 */
public final class StreamKeys {

	public static final String REQUEST_STREAM = "d94:umd:req";
	public static final String REPLY_STREAM_PREFIX = "d94:umd:rep:";
	public static final String LOCAL_ALIVE_KEY = "d94:umd:local:alive";

	public static final String CONSUMER_GROUP = "home-backend";

	/** XADD MAXLEN ~ 값 (근사 trim) */
	public static final long REQUEST_STREAM_MAX_LEN = 10_000L;
	public static final long REPLY_STREAM_MAX_LEN = 1_000L;

	private StreamKeys() {
	}

	public static String replyStream(String instanceId) {
		if (instanceId == null || instanceId.isBlank()) {
			throw new IllegalArgumentException("instanceId는 필수값입니다.");
		}
		return REPLY_STREAM_PREFIX + instanceId;
	}
}
