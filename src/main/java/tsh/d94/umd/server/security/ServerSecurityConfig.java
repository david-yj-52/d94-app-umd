package tsh.d94.umd.server.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import lombok.RequiredArgsConstructor;
import tsh.d94.lib.core.apService.auth.JwtService;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;
import tsh.d94.umd.common.error.UmdErrorCode;
import tsh.d94.umd.server.proxy.ProxyResponse;
import tsh.d94.umd.server.proxy.ProxyResponseWriter;

/**
 * [server] 보안: JWT 사전 확인 (Redis 앞 차단용).
 * <ul>
 *   <li>최종 인증·인가는 백엔드가 같은 토큰(Authorization 원본 전달)으로 다시 검증한다.</li>
 *   <li>CORS 는 백엔드 소유 → 여기서 처리하지 않고, preflight(OPTIONS)는 토큰 없이 통과시켜 백엔드로 전달한다.</li>
 *   <li>umd.server.public-paths 는 토큰 없이 전달 (예: 백엔드 Swagger).</li>
 * </ul>
 * DB 를 쓰지 않으므로 lib-core SecurityConfig(UserDetailsService 의존)는 사용하지 않는다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Import(JwtService.class)
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class ServerSecurityConfig {

	private final JwtService jwtService;
	private final UmdProperties properties;
	private final Environment environment;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		// actuator 가 별도(내부) 포트로 분리된 경우에만 prometheus 등 전체 endpoint 를 인증 없이 연다.
		boolean separateManagementPort = ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;
		String[] publicPaths = properties.getServer().getPublicPaths().toArray(String[]::new);

		http
			.csrf(AbstractHttpConfigurer::disable)
			.cors(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> {
				auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
				auth.requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll();
				if (separateManagementPort) {
					auth.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll();
				}
				if (publicPaths.length > 0) {
					auth.requestMatchers(publicPaths).permitAll();
				}
				auth.anyRequest().authenticated();
			})
			.sessionManagement(session -> session
				.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
			)
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(unauthorizedEntryPoint())
			)
			.addFilterBefore(new UmdJwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/** 401 도 UMD 자체 오류 응답 형식(ApiResponse + X-Umd-Error, 허용 Origin 이면 CORS)으로 쓴다. */
	private AuthenticationEntryPoint unauthorizedEntryPoint() {
		return (request, response, authException) -> ProxyResponseWriter.write(request, response,
			ProxyResponse.error(UmdErrorCode.UNAUTHORIZED, null), properties.getServer().getCorsOrigins());
	}
}
