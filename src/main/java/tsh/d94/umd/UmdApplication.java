package tsh.d94.umd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * UMD 는 DB 없이 동작하는 HTTP ↔ Redis Stream 프록시다 (umd.mode = server | local | both).
 * - lib-core 전체를 스캔하면 JPA/DB 기반 Bean(UserDetailsService, Access 등)이 함께 올라오므로
 *   필요한 Bean만 가져온다 (JwtService 는 server 보안 설정에서만 Import → local 은 JWT 키 불필요).
 * - UMD 자체 API 가 없으므로 Swagger 는 사용하지 않는다 (API 문서는 백엔드가 제공).
 */
@SpringBootApplication(
	scanBasePackages = {"tsh.d94.umd"},
	exclude = {
		DataSourceAutoConfiguration.class,
		DataSourceTransactionManagerAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		UserDetailsServiceAutoConfiguration.class
	})
public class UmdApplication {

	public static void main(String[] args) {
		SpringApplication.run(UmdApplication.class, args);
	}
}
