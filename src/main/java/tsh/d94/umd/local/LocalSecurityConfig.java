package tsh.d94.umd.local;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;

/**
 * local 단독 모드 보안: HTTP API 가 없으므로 앱 포트는 전부 거부하고 actuator 만 연다.
 * actuator 는 별도 포트(8091)이며 publish 하지 않는다 (호스트 내부 전용).
 * both 모드에서는 server 의 보안 설정을 사용한다.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnUmdMode(UmdMode.LOCAL)
public class LocalSecurityConfig {

	@Bean
	public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
				.anyRequest().denyAll())
			.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)));
		return http.build();
	}
}
