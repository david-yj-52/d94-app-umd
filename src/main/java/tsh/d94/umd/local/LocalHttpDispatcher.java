package tsh.d94.umd.local;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.ProxyHeaderPolicy;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.common.error.UmdErrorResponse;
import tsh.d94.umd.common.spec.StreamHttpReply;
import tsh.d94.umd.common.spec.StreamHttpRequest;

/**
 * [local] Stream 요청을 로컬 백엔드 HTTP 호출로 바꾸고, 응답을 Stream 응답으로 만든다.
 * <ul>
 *   <li>method / path / query / headers / body 그대로 호출 (UI 가 직접 부른 것처럼)</li>
 *   <li>타임아웃 = deadline 까지 남은 시간. 넘으면 응답하지 않는다 (server 가 이미 504 응답)</li>
 *   <li>리다이렉트는 따라가지 않고 3xx 를 그대로 전달한다</li>
 *   <li>연결 실패 → 502 LOCAL_BACKEND_UNAVAILABLE, 응답 본문 초과 → 502 RESPONSE_TOO_LARGE</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class LocalHttpDispatcher {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	/** JDK HttpClient 가 직접 설정하는 헤더 (지정하면 예외) */
	private static final Set<String> CLIENT_MANAGED = Set.of("expect", "host", "content-length", "connection",
		"upgrade");

	private final HttpClient httpClient;
	private final UmdProperties properties;
	private final Clock clock;

	@Autowired
	public LocalHttpDispatcher(UmdProperties properties) {
		this(HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.version(HttpClient.Version.HTTP_1_1)
				.build(),
			properties, Clock.systemUTC());
	}

	LocalHttpDispatcher(HttpClient httpClient, UmdProperties properties, Clock clock) {
		this.httpClient = httpClient;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @return 응답. 비어 있으면 응답하지 않는다 (deadline 경과, 인터럽트)
	 */
	public Optional<StreamHttpReply> dispatch(StreamHttpRequest request, UmdProperties.Route route) {
		long remaining = request.getDeadline() - clock.millis();
		if (remaining <= 0) {
			return Optional.empty();
		}
		URI target = targetUri(route.getUri(), request.getPath(), request.getQuery());
		try {
			HttpResponse<InputStream> response = httpClient.send(
				buildRequest(request, target, Duration.ofMillis(remaining)),
				HttpResponse.BodyHandlers.ofInputStream());
			return Optional.of(toReply(request, response));
		} catch (HttpTimeoutException e) {
			log.warn("로컬 백엔드 응답 시간 초과 (응답 생략). corrId={} {} {}", request.getCorrId(), request.getMethod(),
				target);
			return Optional.empty();
		} catch (IOException e) {
			log.warn("로컬 백엔드 연결 실패. corrId={} {} cause={}", request.getCorrId(), target, e.toString());
			return Optional.of(error(request, UmdErrorCode.LOCAL_BACKEND_UNAVAILABLE));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	public StreamHttpReply error(StreamHttpRequest request, UmdErrorCode code) {
		return request.replyBuilder()
			.status(code.getStatus().value())
			.headers(UmdErrorResponse.headers(code))
			.body(UmdErrorResponse.body(code))
			.ts(clock.millis())
			.build();
	}

	private HttpRequest buildRequest(StreamHttpRequest request, URI target, Duration timeout) {
		byte[] body = request.getBody();
		HttpRequest.BodyPublisher publisher = body == null || body.length == 0
			? HttpRequest.BodyPublishers.noBody()
			: HttpRequest.BodyPublishers.ofByteArray(body);
		HttpRequest.Builder builder = HttpRequest.newBuilder(target)
			.timeout(timeout)
			.method(request.getMethod(), publisher);
		ProxyHeaderPolicy.forLocalDispatch(request.getHeaders()).forEach((name, values) -> {
			if (!CLIENT_MANAGED.contains(name)) {
				values.forEach(value -> builder.header(name, value));
			}
		});
		return builder.build();
	}

	private StreamHttpReply toReply(StreamHttpRequest request, HttpResponse<InputStream> response)
		throws IOException {
		long max = properties.getLocal().getMaxResponseBody().toBytes();
		byte[] body;
		try (InputStream in = response.body()) {
			body = in.readNBytes((int)Math.min(Integer.MAX_VALUE - 8, max + 1));
		}
		if (body.length > max) {
			log.warn("로컬 백엔드 응답 본문 초과. corrId={} max={}", request.getCorrId(), max);
			return error(request, UmdErrorCode.RESPONSE_TOO_LARGE);
		}
		Map<String, List<String>> headers = new LinkedHashMap<>();
		response.headers().map().forEach((name, values) -> {
			if (!name.startsWith(":")) {
				headers.put(name, values);
			}
		});
		return request.replyBuilder()
			.status(response.statusCode())
			.headers(ProxyHeaderPolicy.forResponse(headers))
			.body(body)
			.ts(clock.millis())
			.build();
	}

	/** 경로는 바꾸지 않는다. route uri 에 path 가 있으면(백엔드 context-path) 앞에 붙인다. */
	static URI targetUri(URI base, String path, String query) {
		String prefix = base.toString();
		while (prefix.endsWith("/")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		return URI.create(prefix + path + (query == null || query.isEmpty() ? "" : "?" + query));
	}
}
