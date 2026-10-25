# 코딩 컨벤션

규칙의 상세와 **현재 코드에 남아 있는 예외**를 담습니다. 요약과 불변식은 [AGENTS.md](../AGENTS.md) §5·§6 에 있습니다.

## 1. 패키지 · 모드

| 패키지 | 활성 모드 | 조건 |
|---|---|---|
| `tsh.d94.umd.common` (+ `error`, `spec`) | 전부 | 조건 없음 |
| `tsh.d94.umd.server` (+ `proxy`, `security`) | `server`, `both` | `@ConditionalOnUmdMode({UmdMode.SERVER, UmdMode.BOTH})` |
| `tsh.d94.umd.local` | `local`, `both` | `@ConditionalOnUmdMode({UmdMode.LOCAL, UmdMode.BOTH})` |

- Bean 으로 등록되는 server/local 클래스는 위 조건을 **반드시** 붙입니다. local 단독 전용은 `@ConditionalOnUmdMode(UmdMode.LOCAL)` (예: `LocalSecurityConfig`).
- server ↔ local 패키지는 서로 import 하지 않습니다. 둘 다 필요한 코드는 `common` 으로 올립니다.
- Bean 이 아닌 값 객체·유틸(`ProxyRequest`, `StreamCall`, `ProxyHeaderPolicy` 등)은 조건이 필요 없습니다.

## 2. 네이밍

| 유형 | 패턴 | 예시 |
|---|---|---|
| server 전용 클래스 | 역할 이름 (`Stream*`, `Reply*`, `Proxy*`) | `StreamProxyService`, `ReplyStreamListener` |
| local 전용 클래스 | `Local*` 또는 역할 이름 | `LocalRequestProcessor`, `PendingReclaimer` |
| health indicator | `*HealthIndicator`, Bean 이름은 health 항목명 | `@Component("replyStream")` |
| 지표 | `umd_{proxy|stream|local}_*`, 카운터는 `_total`, 시간은 `_seconds` | `umd_local_dispatch_seconds` |
| 오류 코드 | enum 상수 + `UMD_` 접두 코드 문자열 | `NO_ROUTE` / `"UMD_NO_ROUTE"` |
| 테스트 | 단위 `*Test`, Redis 사용 `*IntegrationTest` | `PendingReclaimerIntegrationTest` |

## 3. 포맷 · 스타일

포맷터 설정 파일은 없고 IntelliJ 포맷을 따릅니다.
- 들여쓰기 **탭**.
- import 순서: `java.*` → (빈 줄) → `org.*`·`com.*` → (빈 줄) → `lombok.*`·`tsh.*`.
- 의존성 주입은 생성자 주입. 필드가 모두 final 이면 `@RequiredArgsConstructor`.
- 로그는 `@Slf4j`, 메시지는 한국어 또는 영어 + `key={}` 형식 (예: `"Local route. id={} prefix={} uri={}"`).
- 불변 값은 `record` (`ProxyRequest`, `ProxyResponse`, `StreamCall`), 상수·유틸 클래스는 `final` + private 생성자.

## 4. 주석

- 클래스 Javadoc 에 **책임과 "왜"** 를 한국어로 씁니다. 분기가 여러 개면 `<pre>` 로 입력 → 결과 표를 둡니다 (`LocalRequestProcessor`, `StreamProxyService` 참고).
- 모드 전용 클래스는 Javadoc 첫 줄을 `[server]` / `[local]` 로 시작합니다 (§6 예외 참고).
- 설정 기본값의 이유는 `application.yml` 주석에 씁니다.

## 5. 오류 · 설정 · 관측

- UMD 가 직접 만드는 응답은 `UmdErrorCode` + `UmdErrorResponse` 로만 만듭니다. 새 상황이면 코드를 추가하고 [architecture §7](architecture.md) 표를 갱신합니다.
- 설정은 `UmdProperties` 에 필드를 추가하고 `application.yml` 에 기본값과 주석을 둡니다. 서로 의존하는 값은 `UmdSettingsValidator` 에서 기동 시 검사합니다.
- 지표 태그에 경로·corrId 등 값 종류가 무한한 값을 넣지 않습니다. `status` 는 `2xx` 같은 클래스로.

## 6. 알려진 예외 (따라 하지 말 것)

- **Javadoc `[server]`/`[local]` 접두사 누락:** 모드 전용 클래스 중 일부만 사용합니다. 누락: `LocalAgentHealthIndicator`, `LocalAliveChecker`, `LocalSecurityConfig`, `PendingReplyRegistry`, `ReplyStreamHealthIndicator`, `ReplyStreamListener`, `RequestConsumerHealthIndicator`, `RequestPublisher`, `StreamProxyConfig`(Javadoc 없음), `StreamRequestGateway`. 수정할 때 함께 맞춥니다.
- **테스트 이름:** `BothModeContextTest`, `EndToEndBothModeTest` 는 Redis(Testcontainers)를 쓰지만 `*IntegrationTest` 로 끝나지 않습니다.
