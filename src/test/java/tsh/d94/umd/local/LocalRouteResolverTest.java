package tsh.d94.umd.local;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

import tsh.d94.umd.common.UmdProperties;

class LocalRouteResolverTest {

	private static UmdProperties.Route route(String id, String prefix) {
		UmdProperties.Route route = new UmdProperties.Route();
		route.setId(id);
		route.setPathPrefix(prefix);
		route.setUri(URI.create("http://localhost:8080"));
		return route;
	}

	private LocalRouteResolver resolver(UmdProperties.Route... routes) {
		UmdProperties properties = new UmdProperties();
		properties.getLocal().getRoutes().addAll(java.util.List.of(routes));
		return new LocalRouteResolver(properties);
	}

	private String resolve(LocalRouteResolver resolver, String path) {
		return resolver.resolve(path).map(UmdProperties.Route::getId).orElse(null);
	}

	@Test
	void longest_prefix_wins_regardless_of_order() {
		LocalRouteResolver resolver = resolver(route("default", "/"), route("v1", "/v1"), route("cira", "/v1/cira"));

		assertThat(resolve(resolver, "/v1/cira/issues")).isEqualTo("cira");
		assertThat(resolve(resolver, "/v1/projects")).isEqualTo("v1");
		assertThat(resolve(resolver, "/health")).isEqualTo("default");
	}

	@Test
	void prefix_matches_on_path_segment_boundary() {
		LocalRouteResolver resolver = resolver(route("cira", "/v1/cira"));

		assertThat(resolve(resolver, "/v1/cira")).isEqualTo("cira");
		assertThat(resolve(resolver, "/v1/cira/1")).isEqualTo("cira");
		assertThat(resolve(resolver, "/v1/cirax")).isNull();
	}

	@Test
	void trailing_slash_in_prefix_is_ignored() {
		assertThat(resolve(resolver(route("cira", "/v1/cira/")), "/v1/cira/1")).isEqualTo("cira");
		assertThat(LocalRouteResolver.normalize("v1//")).isEqualTo("/v1");
		assertThat(LocalRouteResolver.normalize("/")).isEqualTo("/");
	}

	@Test
	void no_routes_resolves_nothing() {
		assertThat(resolver().resolve("/v1/items")).isEmpty();
	}
}
