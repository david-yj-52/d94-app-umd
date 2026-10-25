package tsh.d94.umd.server.security;

import java.io.IOException;
import java.util.List;

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
 */
@Slf4j
@RequiredArgsConstructor
public class UmdJwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;

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

		try {
			String userEmail = jwtService.extractEmail(authHeader.substring(BEARER_PREFIX.length()));
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
}
