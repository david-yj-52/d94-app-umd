package tsh.d94.umd.common.spec;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StreamHttpMessageTest {

	private StreamHttpRequest jsonRequest() {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		headers.put("Content-Type", List.of("application/json"));
		headers.put("Accept-Language", List.of("ko", "en"));
		return StreamHttpRequest.builder()
			.corrId("corr-1")
			.replyTo(StreamKeys.replyStream("umd-1"))
			.method("post")
			.path("/v1/items")
			.query("page=1&size=20")
			.headers(headers)
			.body("{\"name\":\"한글\"}".getBytes(StandardCharsets.UTF_8))
			.ts(1_000L)
			.deadline(31_000L)
			.build();
	}

	@Test
	void request_round_trip_with_json_body_as_utf8() {
		Map<String, String> fields = jsonRequest().toFields();

		assertThat(fields)
			.containsEntry(StreamField.METHOD, "POST")
			.containsEntry(StreamField.PATH, "/v1/items")
			.containsEntry(StreamField.QUERY, "page=1&size=20")
			.containsEntry(StreamField.BODY, "{\"name\":\"한글\"}")
			.containsEntry(StreamField.BODY_ENCODING, StreamBodyCodec.UTF8);

		StreamHttpRequest restored = StreamHttpRequest.fromFields(fields);
		assertThat(restored.getMethod()).isEqualTo("POST");
		assertThat(restored.getQuery()).isEqualTo("page=1&size=20");
		assertThat(restored.getHeaders()).containsEntry("accept-language", List.of("ko", "en"));
		assertThat(new String(restored.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"name\":\"한글\"}");
		assertThat(restored.header("Content-Type")).isEqualTo("application/json");
	}

	@Test
	void header_names_are_normalized_to_lowercase() {
		assertThat(jsonRequest().toFields().get(StreamField.HEADERS))
			.contains("\"content-type\"")
			.doesNotContain("Content-Type");
	}

	@Test
	void binary_body_round_trips_as_base64() {
		byte[] png = {(byte)0x89, 'P', 'N', 'G', 0, (byte)0xFF};
		StreamHttpReply reply = StreamHttpReply.builder()
			.corrId("corr-1")
			.status(200)
			.headers(Map.of("content-type", List.of("image/png")))
			.body(png)
			.ts(2_000L)
			.build();

		Map<String, String> fields = reply.toFields();

		assertThat(fields).containsEntry(StreamField.BODY_ENCODING, StreamBodyCodec.BASE64);
		assertThat(StreamHttpReply.fromFields(fields).getBody()).isEqualTo(png);
	}

	@Test
	void empty_body_and_query_are_omitted() {
		StreamHttpRequest get = StreamHttpRequest.builder()
			.corrId("c").replyTo("r").method("GET").path("/v1/items").ts(1).deadline(2)
			.build();

		Map<String, String> fields = get.toFields();

		assertThat(fields).doesNotContainKeys(StreamField.QUERY, StreamField.BODY, StreamField.BODY_ENCODING,
			StreamField.HEADERS);
		StreamHttpRequest restored = StreamHttpRequest.fromFields(fields);
		assertThat(restored.getBody()).isEmpty();
		assertThat(restored.getQuery()).isNull();
		assertThat(restored.getHeaders()).isEmpty();
	}

	@Test
	void reply_round_trip_keeps_status_and_headers() {
		StreamHttpReply reply = jsonRequest().replyBuilder()
			.status(201)
			.headers(Map.of("Location", List.of("/v1/items/43"), "ETag", List.of("\"v1\"")))
			.ts(2_000L)
			.build();

		StreamHttpReply restored = StreamHttpReply.fromFields(reply.toFields());

		assertThat(restored.getCorrId()).isEqualTo("corr-1");
		assertThat(restored.getStatus()).isEqualTo(201);
		assertThat(restored.header("location")).isEqualTo("/v1/items/43");
		assertThat(restored.header("etag")).isEqualTo("\"v1\"");
	}

	@Test
	void request_expired_after_deadline() {
		StreamHttpRequest req = jsonRequest();
		assertThat(req.isExpired(31_000L)).isFalse();
		assertThat(req.isExpired(31_001L)).isTrue();
	}

	@Test
	void missing_required_field_throws_with_field_name() {
		Map<String, String> fields = jsonRequest().toFields();
		fields.remove(StreamField.REPLY_TO);

		assertThatThrownBy(() -> StreamHttpRequest.fromFields(fields))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(StreamField.REPLY_TO);
	}

	@Test
	void relative_path_is_rejected() {
		Map<String, String> fields = jsonRequest().toFields();
		fields.put(StreamField.PATH, "v1/items");

		assertThatThrownBy(() -> StreamHttpRequest.fromFields(fields))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(StreamField.PATH);
	}

	@Test
	void invalid_status_is_rejected() {
		Map<String, String> fields = StreamHttpReply.builder().corrId("c").status(200).ts(1).build().toFields();
		fields.put(StreamField.STATUS, "999");

		assertThatThrownBy(() -> StreamHttpReply.fromFields(fields))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(StreamField.STATUS);
	}

	@Test
	void malformed_headers_json_is_rejected() {
		Map<String, String> fields = jsonRequest().toFields();
		fields.put(StreamField.HEADERS, "{not-json");

		assertThatThrownBy(() -> StreamHttpRequest.fromFields(fields))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(StreamField.HEADERS);
	}

	@Test
	void body_without_encoding_is_rejected() {
		Map<String, String> fields = jsonRequest().toFields();
		fields.remove(StreamField.BODY_ENCODING);

		assertThatThrownBy(() -> StreamHttpRequest.fromFields(fields))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(StreamField.BODY_ENCODING);
	}

	@Test
	void replyStream_requires_instanceId() {
		assertThat(StreamKeys.replyStream("umd-1")).isEqualTo("d94:umd:rep:umd-1");
		assertThatThrownBy(() -> StreamKeys.replyStream(" "))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
