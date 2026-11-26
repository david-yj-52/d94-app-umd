package tsh.d94.umd.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tsh.d94.lib.core.apService.auth.JwtService;

/**
 * DB 조회 없이 JWT 서명과 만료만 검증한다.
 * 토큰은 d94-query 가 발급하며, 동일한 JWT_SECRET_KEY 를 공유해야 한다.
 * (만료/서명 불일치 시 jjwt parser 가 예외를 던진다)
 * <p>
 * {@code Bearer d94_...} (MCP·AI 도구용 API 토큰)은 SHA-256 해시가 Redis allowlist
 * ({@value #API_TOKEN_KEY_PREFIX}{sha256}, delap token-sync 가 1시간마다 채움)에 있을 때만 통과.
 * 서비스 범위·READ/WRITE·폐기 최종 검증은 d94-query. Redis 오류면 통과시키지 않는다(401).
 */
@Slf4j
@RequiredArgsConstructor
public class UmdJwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	static final String API_TOKEN_PREFIX = "d94_";
	static final String API_TOKEN_KEY_PREFIX = "d94:api-token:active:";

	private final JwtService jwtService;
	private final StringRedisTemplate redisTemplate;

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain filterChain
	) throws ServletException, IOException {
		final String authHeader = request.getHeader("Authorization");

		if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = authHeader.substring(BEARER_PREFIX.length());
		if (token.startsWith(API_TOKEN_PREFIX)) {
			authenticateApiToken(token, request);
			filterChain.doFilter(request, response);
			return;
		}

		try {
			String userEmail = jwtService.extractEmail(token);
			if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
				UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
					userEmail,
					null,
					List.of(new SimpleGrantedAuthority("ROLE_USER"))
				);
				authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				SecurityContextHolder.getContext().setAuthentication(authToken);
			}
		} catch (Exception e) {
			// 인증 없이 다음 필터로 진행 -> 보호된 경로는 EntryPoint 에서 401 처리
			log.debug("JWT validation failed: {}", e.getMessage());
		}
		filterChain.doFilter(request, response);
	}

	private void authenticateApiToken(String token, HttpServletRequest request) {
		// 로그에는 표시용 prefix(앞 12자)만 — d94-query·d94-web 이 보여주는 값과 같다
		String prefix = token.substring(0, Math.min(12, token.length()));
		try {
			if (Boolean.TRUE.equals(redisTemplate.hasKey(API_TOKEN_KEY_PREFIX + sha256(token)))) {
				UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
					"api-token:" + prefix, null, List.of(new SimpleGrantedAuthority("ROLE_API_TOKEN")));
				authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				SecurityContextHolder.getContext().setAuthentication(authToken);
			} else {
				log.debug("API token not in allowlist: {}", prefix);
			}
		} catch (Exception e) {
			log.warn("API token allowlist lookup failed ({}): {}", prefix, e.getMessage());
		}
	}

	static String sha256(String raw) throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
	}
}
