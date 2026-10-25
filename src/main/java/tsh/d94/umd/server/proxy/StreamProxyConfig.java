package tsh.d94.umd.server.proxy;

import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import jakarta.servlet.DispatcherType;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;

@Configuration
@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
public class StreamProxyConfig {

	/**
	 * Spring Security(DelegatingFilterProxy, order -100) 뒤, DispatcherServlet 앞에서 모든 요청을 처리한다.
	 */
	@Bean
	public FilterRegistrationBean<StreamProxyFilter> streamProxyFilter(StreamProxyService proxyService,
		UmdProperties properties, Environment environment) {
		String sameManagementBasePath = ManagementPortType.get(environment) == ManagementPortType.SAME
			? environment.getProperty("management.endpoints.web.base-path", "/actuator")
			: null;
		FilterRegistrationBean<StreamProxyFilter> registration = new FilterRegistrationBean<>(
			new StreamProxyFilter(proxyService, properties, sameManagementBasePath));
		registration.addUrlPatterns("/*");
		registration.setDispatcherTypes(DispatcherType.REQUEST);
		registration.setAsyncSupported(true);
		registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
		return registration;
	}
}
