package tsh.d94.umd;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 컨텍스트 문서(AGENTS.md 계열, docs/)와 코드의 기계적 동기화 검사 (AGENTS.md §10, ADR 0001).
 * 실패 메시지의 항목마다 갱신할 문서를 알려준다. 설명이 맞는지는 판별하지 않는다.
 */
class ContextDocsTest {

	private static final Path ROOT = Path.of("").toAbsolutePath();
	private static final Path MAIN_JAVA = ROOT.resolve("src/main/java");
	private static final Path ARCHITECTURE = ROOT.resolve("docs/architecture.md");
	private static final Set<String> IGNORED_DIRS = Set.of(".git", ".idea", ".mvn", "target", "logs", "node_modules");

	private static final Pattern CODE_BLOCK = Pattern.compile("```.*?```", Pattern.DOTALL);
	private static final Pattern ERROR_CODE = Pattern.compile("\"(UMD_[A-Z_]+)\"");
	private static final Pattern PROPERTY_FIELD = Pattern.compile("^\\s*private (?!final )[\\w<>, ]+ (\\w+)( =.*)?;", Pattern.MULTILINE);
	private static final Pattern METER = Pattern.compile("(Counter|Timer|Gauge)\\.builder\\(\"(umd\\.[a-z.]+)\"");
	private static final Pattern ENV_KEY = Pattern.compile("^([A-Z][A-Z0-9_]*)=", Pattern.MULTILINE);

	@Test
	void links_resolve_inside_repository() {
		List<String> errors = new ArrayList<>();
		for (Path md : markdownFiles()) {
			for (String target : linkTargets(stripCodeBlocks(read(md)))) {
				if (target.matches("^(https?:|mailto:|#).*")) {
					continue;
				}
				String file = target.split("#", 2)[0];
				if (file.isEmpty()) {
					continue;
				}
				Path resolved = md.getParent().resolve(URLDecoder.decode(file, StandardCharsets.UTF_8)).normalize();
				if (!resolved.startsWith(ROOT)) {
					errors.add(rel(md) + " → " + target + " (저장소 밖 경로. 다른 저장소는 GitHub URL 또는 코드 표기로)");
				} else if (!Files.exists(resolved)) {
					errors.add(rel(md) + " → " + target + " (대상 없음)");
				}
			}
		}
		assertThat(errors).as("[링크] 끊긴 링크").isEmpty();
	}

	@Test
	void every_agents_md_has_claude_md_pointer() {
		List<String> errors = new ArrayList<>();
		for (Path agents : agentsFiles()) {
			Path claude = agents.resolveSibling("CLAUDE.md");
			if (!Files.exists(claude)) {
				errors.add(rel(claude) + " 없음 (내용: @AGENTS.md)");
			} else if (!read(claude).contains("@AGENTS.md")) {
				errors.add(rel(claude) + " 에 @AGENTS.md import 없음");
			}
		}
		assertThat(errors).as("[짝] AGENTS.md / CLAUDE.md").isEmpty();
	}

	@Test
	void every_agents_md_is_on_the_l1_map() {
		String l1 = read(ROOT.resolve("AGENTS.md"));
		String map = l1.substring(l1.indexOf("## 0."), l1.indexOf("## 1."));
		List<String> errors = agentsFiles().stream()
			.filter(p -> !p.getParent().equals(ROOT))
			.filter(p -> !map.contains(p.getParent().getFileName() + "/AGENTS.md"))
			.map(p -> rel(p) + " 가 AGENTS.md §0 문서 지도에 없음")
			.toList();
		assertThat(errors).as("[지도] L1 §0").isEmpty();
	}

	@Test
	void every_main_class_is_listed_in_nearest_agents_md() {
		List<String> errors = new ArrayList<>();
		try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
			for (Path java : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String name = java.getFileName().toString().replace(".java", "");
				Path doc = nearestAgents(java);
				if (!Pattern.compile("\\b" + name + "\\b").matcher(read(doc)).find()) {
					errors.add(rel(java) + " 가 " + rel(doc) + " 클래스 표에 없음");
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		assertThat(errors).as("[클래스] L2 클래스 표").isEmpty();
	}

	@Test
	void every_error_code_is_documented() {
		String source = read(MAIN_JAVA.resolve("tsh/d94/umd/common/error/UmdErrorCode.java"));
		assertThat(missingIn(ARCHITECTURE, matches(ERROR_CODE, source, 1)))
			.as("[오류 코드] UmdErrorCode → docs/architecture.md §7").isEmpty();
	}

	@Test
	void every_umd_property_is_documented() {
		String source = read(MAIN_JAVA.resolve("tsh/d94/umd/common/UmdProperties.java"));
		Set<String> keys = new LinkedHashSet<>();
		for (String field : matches(PROPERTY_FIELD, source, 1)) {
			keys.add(field.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT));
		}
		assertThat(missingIn(ARCHITECTURE, keys)).as("[설정] UmdProperties → docs/architecture.md §11").isEmpty();
	}

	@Test
	void every_metric_is_documented() {
		Set<String> names = new LinkedHashSet<>();
		try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
			for (Path java : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Matcher m = METER.matcher(read(java));
				while (m.find()) {
					String base = m.group(2).replace('.', '_');
					names.add(switch (m.group(1)) {
						case "Counter" -> base + "_total";
						case "Timer" -> base + "_seconds";
						default -> base;
					});
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		assertThat(names).as("지표 추출").isNotEmpty();
		assertThat(missingIn(ARCHITECTURE, names)).as("[지표] Prometheus 이름 → docs/architecture.md §10").isEmpty();
	}

	@Test
	void every_env_example_key_is_documented() {
		String docs = read(ROOT.resolve("docs/dev-setup.md")) + read(ROOT.resolve("docs/deployment.md"));
		List<String> errors = new ArrayList<>();
		for (String example : List.of(
			"src/main/resources/server/.env.server.example",
			"src/main/resources/delap/.env.delap.example")) {
			for (String key : matches(ENV_KEY, read(ROOT.resolve(example)), 1)) {
				if (!docs.contains(key)) {
					errors.add(example + " 의 " + key);
				}
			}
		}
		assertThat(errors).as("[환경 변수] → docs/dev-setup.md 또는 docs/deployment.md").isEmpty();
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private static List<Path> markdownFiles() {
		List<Path> result = new ArrayList<>();
		walk(ROOT, result);
		return result;
	}

	private static void walk(Path dir, List<Path> out) {
		try (Stream<Path> children = Files.list(dir)) {
			for (Path p : children.toList()) {
				if (Files.isDirectory(p)) {
					if (!IGNORED_DIRS.contains(p.getFileName().toString())) {
						walk(p, out);
					}
				} else if (p.toString().endsWith(".md")) {
					out.add(p);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<Path> agentsFiles() {
		return markdownFiles().stream().filter(p -> p.getFileName().toString().equals("AGENTS.md")).toList();
	}

	private static Path nearestAgents(Path file) {
		for (Path dir = file.getParent(); dir.startsWith(ROOT); dir = dir.getParent()) {
			Path candidate = dir.resolve("AGENTS.md");
			if (Files.exists(candidate)) {
				return candidate;
			}
		}
		return ROOT.resolve("AGENTS.md");
	}

	/** 괄호 균형을 맞춰 링크 대상을 추출 — "(main)" 같은 경로 지원 */
	private static List<String> linkTargets(String text) {
		List<String> targets = new ArrayList<>();
		int i = 0;
		while ((i = text.indexOf("](", i)) != -1) {
			int j = i + 2;
			int depth = 1;
			while (j < text.length()) {
				char c = text.charAt(j);
				if (c == '(') {
					depth++;
				} else if (c == ')' && --depth == 0) {
					break;
				}
				j++;
			}
			targets.add(text.substring(i + 2, j));
			i = j;
		}
		return targets;
	}

	private static String stripCodeBlocks(String text) {
		return CODE_BLOCK.matcher(text).replaceAll("");
	}

	private static Set<String> matches(Pattern pattern, String text, int group) {
		Set<String> result = new LinkedHashSet<>();
		Matcher m = pattern.matcher(text);
		while (m.find()) {
			result.add(m.group(group));
		}
		return result;
	}

	private static List<String> missingIn(Path doc, Set<String> names) {
		String text = read(doc);
		return names.stream().filter(n -> !text.contains(n)).toList();
	}

	private static String read(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String rel(Path p) {
		return ROOT.relativize(p).toString().replace('\\', '/');
	}
}
