package tsh.d94.umd.common;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import tsh.d94.umd.common.spec.StreamKeys;

/**
 * umd.* 설정. server / local 하위 설정은 해당 모드에서만 사용된다.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "umd")
public class UmdProperties {

	@NotNull
	private UmdMode mode = UmdMode.SERVER;

	/** 인스턴스마다 고유. server: 응답 Stream 이름, local: Consumer 이름 */
	@NotBlank
	private String instanceId;

	@Valid
	private final Server server = new Server();

	@Valid
	private final Local local = new Local();

	/** server 모드의 응답 Stream (d94:umd:rep:{instanceId}) */
	public String getReplyStream() {
		return StreamKeys.replyStream(instanceId);
	}

	@Data
	public static class Server {

		/** local 응답 대기 시간 (요청 deadline 으로도 사용) */
		private Duration requestTimeout = Duration.ofSeconds(30);

		/** 응답 Stream XREAD BLOCK 시간 */
		private Duration pollTimeout = Duration.ofSeconds(2);

		private DataSize maxRequestBody = DataSize.ofMegabytes(1);

		/** 응답 대기 요청 상한. 넘으면 즉시 503 */
		private int maxPending = 500;

		/** JWT 없이 허용할 경로 패턴 */
		private List<String> publicPaths = new ArrayList<>();

		/** local heartbeat 가 없으면 즉시 503 */
		private boolean requireLocalAlive = true;

		/**
		 * UMD 자체 오류 응답(401/413/503/504 등)에 최소 CORS 헤더를 붙일 Origin. 백엔드 CORS 허용 목록과 같게 둔다.
		 * (정상 응답의 CORS 는 백엔드가 처리)
		 */
		private List<String> corsOrigins = new ArrayList<>();
	}

	@Data
	public static class Local {

		/** 동시 처리 상한 */
		private int consumerConcurrency = 16;

		/** 요청 Stream XREADGROUP BLOCK 시간 */
		private Duration pollTimeout = Duration.ofSeconds(2);

		private DataSize maxResponseBody = DataSize.ofMegabytes(5);

		/** 이 시간 이상 ACK 되지 않은 Pending 엔트리를 회수 대상으로 본다 */
		private Duration reclaimIdle = Duration.ofSeconds(60);

		/** Pending 회수 주기 */
		private Duration reclaimInterval = Duration.ofSeconds(30);

		/** path-prefix 최장 일치 라우팅 */
		@Valid
		private List<Route> routes = new ArrayList<>();
	}

	@Data
	public static class Route {

		@NotBlank
		private String id;

		@NotBlank
		private String pathPrefix;

		@NotNull
		private URI uri;
	}
}
