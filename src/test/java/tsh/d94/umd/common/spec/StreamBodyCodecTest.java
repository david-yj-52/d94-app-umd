package tsh.d94.umd.common.spec;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StreamBodyCodecTest {

	@ParameterizedTest
	@ValueSource(strings = {
		"application/json",
		"application/json; charset=UTF-8",
		"text/plain",
		"text/html;charset=utf-8",
		"application/problem+json",
		"application/vnd.api+json",
		"application/xml",
		"application/x-www-form-urlencoded"
	})
	void utf8_text_types_are_encoded_as_utf8(String contentType) {
		assertThat(StreamBodyCodec.isUtf8Text(contentType)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"image/png",
		"application/octet-stream",
		"application/pdf",
		"multipart/form-data; boundary=abc",
		"text/plain; charset=ISO-8859-1"
	})
	void binary_or_non_utf8_types_are_encoded_as_base64(String contentType) {
		assertThat(StreamBodyCodec.isUtf8Text(contentType)).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	void missing_content_type_falls_back_to_base64(String contentType) {
		StreamBodyCodec.Encoded encoded = StreamBodyCodec.encode("abc".getBytes(StandardCharsets.UTF_8), contentType);

		assertThat(encoded.encoding()).isEqualTo(StreamBodyCodec.BASE64);
	}

	@Test
	void non_utf8_text_round_trips_without_loss() {
		byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);

		StreamBodyCodec.Encoded encoded = StreamBodyCodec.encode(latin1, "text/plain; charset=ISO-8859-1");

		assertThat(StreamBodyCodec.decode(encoded.value(), encoded.encoding())).isEqualTo(latin1);
	}

	@Test
	void empty_body_encodes_to_null_and_decodes_to_empty() {
		assertThat(StreamBodyCodec.encode(new byte[0], "application/json")).isNull();
		assertThat(StreamBodyCodec.encode(null, "application/json")).isNull();
		assertThat(StreamBodyCodec.decode(null, null)).isEmpty();
	}

	@Test
	void invalid_base64_is_rejected() {
		assertThatThrownBy(() -> StreamBodyCodec.decode("@@not-base64@@", StreamBodyCodec.BASE64))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unknown_encoding_is_rejected() {
		assertThatThrownBy(() -> StreamBodyCodec.decode("abc", "gzip"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("gzip");
	}
}
