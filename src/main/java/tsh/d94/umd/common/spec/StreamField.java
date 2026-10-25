package tsh.d94.umd.common.spec;

/**
 * Stream 엔트리 필드명.
 * Redis Insight 에서 바로 읽을 수 있도록 엔트리는 flat 한 String Map 으로 저장한다.
 * (headers 는 JSON 문자열, body 는 bodyEncoding 에 따라 utf8 문자열 또는 base64)
 */
public final class StreamField {

	// 공통
	public static final String CORR_ID = "corrId";
	public static final String HEADERS = "headers";
	public static final String BODY = "body";
	public static final String BODY_ENCODING = "bodyEncoding";
	public static final String TS = "ts";

	// 요청 전용
	public static final String REPLY_TO = "replyTo";
	public static final String METHOD = "method";
	public static final String PATH = "path";
	public static final String QUERY = "query";
	public static final String DEADLINE = "deadline";

	// 응답 전용
	public static final String STATUS = "status";

	private StreamField() {
	}
}
