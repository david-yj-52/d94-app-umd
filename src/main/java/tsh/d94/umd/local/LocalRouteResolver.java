package tsh.d94.umd.local;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tsh.d94.umd.common.ConditionalOnUmdMode;
import tsh.d94.umd.common.UmdMode;
import tsh.d94.umd.common.UmdProperties;

/**
 * [local] 요청 경로 → 로컬 서비스 (umd.local.routes, path-prefix 최장 일치).
 * 경로 경계 단위로 일치시킨다: "/v1/cira" 는 "/v1/cira", "/v1/cira/..." 와 일치하고 "/v1/cirax" 와는 일치하지 않는다.
 * 경로 자체는 바꾸지 않는다 (미래에 Ingress / Gateway 규칙으로 그대로 옮김).
 */
@Slf4j
@Component
@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})
public class LocalRouteResolver {

	private final List<UmdProperties.Route> routes;

	public LocalRouteResolver(UmdProperties properties) {
		this.routes = properties.getLocal().getRoutes().stream()
			.sorted(Comparator.comparingInt((UmdProperties.Route r) -> normalize(r.getPathPrefix()).length()).reversed())
			.toList();
		if (routes.isEmpty()) {
			log.warn("umd.local.routes 가 비어 있습니다. 모든 요청이 404 UMD_NO_ROUTE 로 응답됩니다.");
		}
		routes.forEach(r -> log.info("Local route. id={} prefix={} uri={}", r.getId(), r.getPathPrefix(), r.getUri()));
	}

	public Optional<UmdProperties.Route> resolve(String path) {
		if (path == null) {
			return Optional.empty();
		}
		return routes.stream().filter(route -> matches(normalize(route.getPathPrefix()), path)).findFirst();
	}

	static boolean matches(String prefix, String path) {
		if (prefix.equals("/")) {
			return true;
		}
		return path.equals(prefix) || path.startsWith(prefix + "/");
	}

	/** 끝의 "/" 제거 ("/" 자체는 유지) */
	static String normalize(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			return "/";
		}
		String p = prefix.startsWith("/") ? prefix : "/" + prefix;
		while (p.length() > 1 && p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		return p;
	}
}
