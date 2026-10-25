# L2 · server (`tsh.d94.umd.server`, `.proxy`, `.security`)

> 계층: [L1](../../../../../../../AGENTS.md) › **L2 server** · 활성 모드: `server`, `both`
> 상세: [architecture §3 흐름 · §7 오류 · §8 신뢰성 · §9 보안 · §10 관측](../../../../../../../docs/architecture.md)

## 역할

AP-01 에서 UI 의 모든 요청(`/**`)을 받아 JWT 사전 확인 → 사전 차단 → 요청 Stream 발행 → 자기 응답 Stream 에서 응답을 받아 HTTP 로 복원합니다.

## 클래스

| 클래스 | 책임 | 관련 |
|---|---|---|
| `proxy.StreamProxyConfig` | `StreamProxyFilter` 등록 (`/*`, async, Security 뒤·DispatcherServlet 앞) | [ADR 0004](../../../../../../../docs/decisions/0004-servlet-filter-proxy.md) |
| `proxy.StreamProxyFilter` | 서블릿 필터. 요청을 `ProxyRequest` 로 읽고 AsyncContext 로 응답 대기 | `max-request-body` |
| `proxy.StreamProxyService` | 사전 차단 → 발행 → 응답/오류 매핑 (`classify`) | 413/503/504/502 |
| `proxy.ProxyRequest` / `proxy.ProxyResponse` | 서블릿과 분리된 요청·응답 값 (record) | |
| `proxy.ProxyResponseWriter` | 서블릿 응답 쓰기. UMD 오류에만 허용 Origin 최소 CORS | `cors-origins` |
| `security.ServerSecurityConfig` | JWT 사전 확인, OPTIONS·`public-paths` 통과, `JwtService` Import | I3 |
| `security.UmdJwtAuthenticationFilter` | DB 없이 서명·만료만 검증 (d94-query 와 같은 키) | 401 `UMD_UNAUTHORIZED` |
| `StreamRequestGateway` | corrId 발급, 대기 등록 → 발행, `StreamCall` 반환 | |
| `RequestPublisher` | 요청 Stream XADD (MAXLEN ~10000) | |
| `PendingReplyRegistry` | corrId → Future. 응답·타임아웃·실패 중 먼저 온 것으로 완료 | `max-pending`, `umd_stream_pending` |
| `ReplyStreamListener` | 응답 Stream XREAD → Future 완료 → XDEL, TTL 갱신, 형식 오류 즉시 502 | SmartLifecycle |
| `LocalAliveChecker` | 생존 신호 확인 (1초 캐시) | `require-local-alive` |
| `StreamCall` | 발행 결과 (corrId + Future) | |
| `StreamBadReplyException` | 응답 형식 오류 (corrId 는 읽힘) → 502 | |
| `StreamShutdownException` | 종료로 대기 중단 → 503 | |
| `ServerStreamMetrics` | `umd_proxy_*`, `umd_stream_*` 지표 | I10 |
| `ReplyStreamHealthIndicator` | health `replyStream` — 리스너 동작 여부 | |
| `LocalAgentHealthIndicator` | health `localAgent` — 정보용, 항상 UP | |

## 흐름 — 요청 한 건

```
StreamProxyFilter (JWT 는 앞단 Security 에서 확인, 실패 시 401)
 └─ StreamProxyService.proxy
     ├─ precheck: 본문 초과 413 → 대기 상한 503 OVERLOADED → 생존 신호 없음 503 LOCAL_UNAVAILABLE
     │            (Redis 오류면 503 REDIS_UNAVAILABLE) — 여기까지는 Redis 에 싣지 않음
     ├─ ProxyHeaderPolicy.forRequest + withForwarded
     ├─ StreamRequestGateway.request → PendingReplyRegistry 등록 → RequestPublisher XADD
     └─ Future 완료 시
         ├─ 응답  → ProxyResponse.passthrough (상태·헤더·본문 그대로 + X-Trace-Id)
         └─ 예외  → classify: Timeout 504 / Shutdown 503 SHUTTING_DOWN / BadReply 502 BAD_REPLY / DataAccess 503 REDIS_UNAVAILABLE / 그 외 500 INTERNAL
ReplyStreamListener: XREAD d94:umd:rep:{instanceId} → registry 완료 → XDEL
```

## 이 패키지의 불변식

- **발행 전에 대기 등록**합니다 (응답이 등록보다 먼저 와도 잃지 않도록).
- 사전 차단(413/503)된 요청은 Redis 에 싣지 않습니다.
- 백엔드 응답은 `forResponse` 의 hop-by-hop 제거 외에 손대지 않습니다 (I8).
- 종료 순서: 처리 중 요청 응답 → 리스너 정지 → 남은 대기 503 → 응답 Stream 삭제. `ReplyStreamListener` 의 lifecycle phase 를 바꾸지 마세요.

## 주의 · 예외

- `ServerSecurityConfig` 는 lib-core `SecurityConfig` 를 쓰지 않습니다 (`UserDetailsService`·DB 의존).
- 응답 Stream 은 인스턴스별이므로 server 가 여러 대면 `UMD_INSTANCE_ID` 가 모두 달라야 합니다.
