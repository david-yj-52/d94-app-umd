# L2 · 테스트 (`src/test/java/tsh/d94/umd`)

> 계층: [L1](../../../../../../AGENTS.md) › **L2 test**
> 실행: `./mvnw -B clean verify` — **Docker 필요**, 결과의 `Skipped: 0` 확인 ([dev-setup](../../../../../../docs/dev-setup.md))

## 테스트 계층

| 계층 | 방식 | 클래스 |
|---|---|---|
| 단위 | 스프링 없이 `new UmdProperties()` 등으로 직접 구성 | `ProxyHeaderPolicyTest`, `UmdModeConditionTest`, `StreamBodyCodecTest`, `StreamHttpMessageTest`, `LocalRouteResolverTest`, `LocalHttpDispatcherTest`(JDK `HttpServer` 스텁), `LocalRequestProcessorTest`, `PendingReplyRegistryTest`, `StreamRequestGatewayTest`, `ReplyStreamHealthIndicatorTest`, `StreamProxyServiceTest` |
| Redis 통합 | Testcontainers `redis:7-alpine` + `@DynamicPropertySource` | `StreamRoundTripIntegrationTest`, `PendingReclaimerIntegrationTest`, `ServerProxyIntegrationTest`(HTTP 포함), `LocalModeIntegrationTest` |
| 종단 (both) | `umd.mode=both` + Redis + 스텁 백엔드 | `BothModeContextTest`, `EndToEndBothModeTest` (직결 동등성 포함) |
| 문서 | 컨텍스트 문서 ↔ 코드 대조 | `ContextDocsTest` ([ADR 0001](../../../../../../docs/decisions/0001-context-docs.md)) |
| 지원 | 테스트용 local 대역 | `support.FakeLocalConsumer` |

## 규칙

- Redis 를 쓰는 테스트는 `@Testcontainers(disabledWithoutDocker = true)` + `static final GenericContainer<?> REDIS` 패턴을 따릅니다.
- server 쪽만 검증할 때는 실제 local 대신 `FakeLocalConsumer(redisTemplate, responder)` 를 씁니다. responder 가 `null` 을 반환하면 응답하지 않습니다 (타임아웃 재현).
- 시간에 의존하는 로직은 `Clock` 을 주입받는 package-private 생성자로 테스트합니다 (`LocalRequestProcessor` 참고).
- 랜덤 포트(0)로 management 포트 분리 여부를 판정하지 말고 `ManagementPortType` 을 씁니다.
- 새 기능은 단계마다 테스트를 함께 작성합니다 (별도 테스트 단계 없음). 새 Redis 통합 테스트는 `*IntegrationTest` 로 이름 짓습니다.
- 불변식(L1 §5)을 바꾸는 코드에는 그 불변식을 직접 검증하는 테스트가 있어야 합니다. 예: deadline 경과 시 백엔드 미호출(`LocalRequestProcessorTest`), local 의 JWT 키 없는 기동(`LocalModeIntegrationTest`).

## 주의

- `EndToEndBothModeTest` 는 UMD 경유 응답과 백엔드 직접 호출 응답이 같은지(직결 동등성, L1 §1 최종 목표)를 확인합니다. 실패하면 투명성(I1·I8)이 깨진 것입니다.
- 일부 테스트는 의도적으로 ERROR 로그를 남깁니다 (잘못된 엔트리·응답 형식 오류 시나리오). 실패가 아닙니다.
