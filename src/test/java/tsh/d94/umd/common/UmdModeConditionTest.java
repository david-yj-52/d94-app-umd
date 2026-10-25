package tsh.d94.umd.common;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class UmdModeConditionTest {

	static class ServerFeature {
	}

	static class LocalFeature {
	}

	static class LocalOnlyFeature {
	}

	@Configuration
	static class ModeBeans {

		@Bean
		@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})
		ServerFeature serverFeature() {
			return new ServerFeature();
		}

		@Bean
		@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
		LocalFeature localFeature() {
			return new LocalFeature();
		}

		@Bean
		@ConditionalOnUmdMode(UmdMode.LOCAL)
		LocalOnlyFeature localOnlyFeature() {
			return new LocalOnlyFeature();
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ModeBeans.class);

	@Test
	void default_mode_is_server() {
		runner.run(ctx -> assertThat(ctx)
			.hasSingleBean(ServerFeature.class)
			.doesNotHaveBean(LocalFeature.class)
			.doesNotHaveBean(LocalOnlyFeature.class));
	}

	@Test
	void local_mode_registers_local_beans_only() {
		runner.withPropertyValues("umd.mode=local").run(ctx -> assertThat(ctx)
			.doesNotHaveBean(ServerFeature.class)
			.hasSingleBean(LocalFeature.class)
			.hasSingleBean(LocalOnlyFeature.class));
	}

	@Test
	void both_mode_registers_server_and_local_but_not_local_only() {
		runner.withPropertyValues("umd.mode=BOTH").run(ctx -> assertThat(ctx)
			.hasSingleBean(ServerFeature.class)
			.hasSingleBean(LocalFeature.class)
			.doesNotHaveBean(LocalOnlyFeature.class));
	}

	@Test
	void invalid_mode_fails_startup() {
		runner.withPropertyValues("umd.mode=proxy").run(ctx -> assertThat(ctx)
			.hasFailed()
			.getFailure().hasStackTraceContaining("umd.mode 값이 올바르지 않습니다"));
	}

	@Test
	void properties_bind_with_defaults_and_routes() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
			.withUserConfiguration(UmdPropertiesOnly.class)
			.withPropertyValues(
				"umd.mode=local",
				"umd.instance-id=umd-home01",
				"umd.local.routes[0].id=default",
				"umd.local.routes[0].path-prefix=/",
				"umd.local.routes[0].uri=http://localhost:8080")
			.run(ctx -> {
				UmdProperties props = ctx.getBean(UmdProperties.class);
				assertThat(props.getMode()).isEqualTo(UmdMode.LOCAL);
				assertThat(props.getServer().getRequestTimeout().getSeconds()).isEqualTo(30);
				assertThat(props.getServer().getMaxRequestBody().toMegabytes()).isEqualTo(1);
				assertThat(props.getLocal().getMaxResponseBody().toMegabytes()).isEqualTo(5);
				assertThat(props.getLocal().getRoutes()).singleElement()
					.satisfies(r -> assertThat(r.getUri().toString()).isEqualTo("http://localhost:8080"));
			});
	}

	@Test
	void blank_instance_id_fails_validation() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
			.withUserConfiguration(UmdPropertiesOnly.class)
			.withPropertyValues("umd.instance-id= ")
			.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void poll_timeout_must_be_shorter_than_redis_command_timeout() {
		new ApplicationContextRunner()
			.withUserConfiguration(UmdPropertiesOnly.class, UmdSettingsValidator.class)
			.withPropertyValues("umd.instance-id=x", "spring.data.redis.timeout=2s",
				"umd.server.poll-timeout=1s", "umd.local.poll-timeout=2s")
			.run(ctx -> assertThat(ctx).hasFailed()
				.getFailure().hasStackTraceContaining("umd.local.poll-timeout"));

		new ApplicationContextRunner()
			.withUserConfiguration(UmdPropertiesOnly.class, UmdSettingsValidator.class)
			.withPropertyValues("umd.instance-id=x", "spring.data.redis.timeout=3s")
			.run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Configuration
	@org.springframework.boot.context.properties.EnableConfigurationProperties(
		{UmdProperties.class, org.springframework.boot.autoconfigure.data.redis.RedisProperties.class})
	static class UmdPropertiesOnly {
	}
}
