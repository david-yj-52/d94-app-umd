package tsh.d94.umd.server.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;

/**
 * [server] 앱 포트의 모든 요청(/*)을 Stream 프록시로 처리하는 서블릿 필터.
 *
 * <p>Spring MVC 컨트롤러(@RequestMapping("/**")) 대신 필터를 쓰는 이유:
 * <ul>
 *   <li>MVC 는 CORS preflight(OPTIONS + Origin + Access-Control-Request-Method)를 핸들러에 넘기지 않고 직접 응답한다.
 *       CORS 는 백엔드 소유이므로 preflight 도 그대로 전달해야 한다.</li>
 *   <li>메시지 컨버터·리소스 핸들러 없이 바이트 그대로 주고받는다 (투명 프록시).</li>
 * </ul>
 * 보안 필터(JWT) 뒤에서 실행되며, 응답은 서블릿 비동기(AsyncContext)로 써서 대기 중 스레드를 점유하지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
public class StreamProxyFilter extends OncePerRequestFilter {

	/** UMD 응답 타임아웃 이후 여유. 이 시간 안에 UMD 가 504 를 직접 만든다. */
	private static final Duration ASYNC_GRACE = Duration.ofSeconds(30);

	private final StreamProxyService proxyService;
	private final UmdProperties properties;
	/** management 포트가 앱 포트와 같을 때 actuator 경로는 프록시하지 않는다. null 이면 해당 없음 */
	private final String sameManagementBasePath;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return sameManagementBasePath != null && request.getRequestURI().startsWith(sameManagementBasePath);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws IOException {
		ProxyRequest proxyRequest = toProxyRequest(request);

		AsyncContext async = request.startAsync();
		async.setTimeout(properties.getServer().getRequestTimeout().plus(ASYNC_GRACE).toMillis());

		proxyService.proxy(proxyRequest).whenComplete((proxyResponse, error) -> {
			ProxyResponse result = proxyResponse != null
				? proxyResponse
				: ProxyResponse.error(UmdErrorCode.INTERNAL, null);
			if (error != null) {
				log.error("프록시 처리 중 예외", error);
			}
			try {
				ProxyResponseWriter.write(request, (HttpServletResponse)async.getResponse(), result,
					properties.getServer().getCorsOrigins());
			} catch (IOException | IllegalStateException e) {
				log.debug("응답 쓰기 실패 (클라이언트 연결 종료 등). cause={}", e.getMessage());
			} finally {
				async.complete();
			}
		});
	}

	private ProxyRequest toProxyRequest(HttpServletRequest request) throws IOException {
		long max = properties.getServer().getMaxRequestBody().toBytes();
		byte[] body = new byte[0];
		boolean tooLarge = request.getContentLengthLong() > max;
		if (!tooLarge) {
			body = readUpTo(request.getInputStream(), max + 1);
			tooLarge = body.length > max;
			if (tooLarge) {
				body = new byte[0];
			}
		}
		return new ProxyRequest(
			request.getMethod(),
			request.getRequestURI(),
			request.getQueryString(),
			headers(request),
			body,
			tooLarge,
			request.getRemoteAddr(),
			request.getScheme(),
			request.getHeader("Host"));
	}

	private static Map<String, List<String>> headers(HttpServletRequest request) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (String name : Collections.list(request.getHeaderNames())) {
			headers.put(name, Collections.list(request.getHeaders(name)));
		}
		return headers;
	}

	private static byte[] readUpTo(InputStream in, long limit) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long total = 0;
		int read;
		while (total < limit && (read = in.read(buffer, 0, (int)Math.min(buffer.length, limit - total))) != -1) {
			out.write(buffer, 0, read);
			total += read;
		}
		return out.toByteArray();
	}
}
