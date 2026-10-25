# L2 · local (`tsh.d94.umd.local`)

> 계층: [L1](../../../../../../../AGENTS.md) › **L2 local** · 활성 모드: `local`, `both` (`LocalSecurityConfig` 만 `local` 단독)
> 상세: [architecture §3 흐름 · §8 신뢰성 · §10 관측](../../../../../../../docs/architecture.md) · 라우팅 예: `config/routes.example.yml`

## 역할

Home PC 에서 요청 Stream 을 소비해 로컬 백엔드를 **UI 가 직접 부른 것처럼** REST 호출하고, 응답을 server 의 응답 Stream 에 돌려줍니다. 앱 포트로 들어오는 요청은 받지 않습니다.

## 클래스

| 클래스 | 책임 | 관련 |
|---|---|---|
| `RequestStreamConsumer` | XREADGROUP 루프. 세마포어 여유만큼만 읽고 가상 스레드에서 처리, Redis 장애 2초 backoff, NOGROUP 재생성 | `consumer-concurrency`, `poll-timeout` |
| `LocalRequestProcessor` | 엔트리 1건: 형식 확인 → **deadline 확인** → 라우팅 → 호출 → 응답 발행 → XACK+XDEL | I6, I7 |
| `LocalRouteResolver` | path-prefix 최장 일치 (경로 경계 단위), 경로 미변경 | `routes`, 404 `UMD_NO_ROUTE` |
| `LocalHttpDispatcher` | JDK HttpClient 호출. 타임아웃 = 남은 deadline, 리다이렉트 미추종 | 502 `LOCAL_BACKEND_UNAVAILABLE` / `RESPONSE_TOO_LARGE` |
| `ReplyPublisher` | 응답을 `replyTo` 에 XADD (MAXLEN ~1000) | |
| `PendingReclaimer` | XPENDING + XCLAIM 으로 ACK 안 된 엔트리 회수 | `reclaim-idle`, `reclaim-interval` |
| `LocalHeartbeat` | 5초마다 `d94:umd:local:alive` SET EX 15, 종료 시 자기 값만 삭제 | server 즉시 503 판단 |
| `LocalSecurityConfig` | local 단독: 앱 포트 전부 거부, actuator 만 | |
| `LocalMetrics` | `umd_local_*` 지표 | I10 |
| `RequestConsumerHealthIndicator` | health `requestConsumer` — 루프 동작 + 최근 10초 읽기 실패 없음 | |

## 흐름 — 엔트리 한 건 (`LocalRequestProcessor.process`)

```
fromFields 실패        → 지표 invalid, XACK+XDEL (replyTo 를 모를 수 있어 응답 불가)
deadline 경과          → 지표 expired, XACK+XDEL (응답 없음 — server 는 이미 504)
route 없음             → 404 UMD_NO_ROUTE 응답
route 있음             → LocalHttpDispatcher.dispatch
                          ├ 응답 → 그대로 (3xx 포함)
                          ├ 연결 실패 / 본문 초과 → 502
                          └ 시간 초과 → 응답 없음
응답이 있으면 XADD      → 실패하면 return (ACK 보류 → Pending → PendingReclaimer 가 회수)
XACK + XDEL
```

## 이 패키지의 불변식

- **deadline 확인이 라우팅·호출보다 먼저** (회수된 엔트리 포함). 순서를 바꾸면 타임아웃된 POST 가 다시 실행됩니다 (I6).
- **응답 발행 실패 시 ACK 하지 않습니다** (I7). ACK 는 `acknowledge()` 한 곳에서만.
- 경로·쿼리·헤더·본문을 바꾸지 않습니다. 새 로컬 서비스는 코드가 아니라 `routes` 로 (I1·I2).
- 처리 동시성은 세마포어로 제한합니다. 읽기 개수를 늘려 백엔드를 과부하시키지 마세요.

## 주의 · 예외

- `both` 모드에서는 `LocalSecurityConfig` 가 빠지고 server 보안 설정을 씁니다.
- `routes` 는 `LocalRouteResolver` 생성 시(기동 시) 한 번 읽습니다. 바꾸면 재기동해야 합니다.
- **백엔드 호출 도중 local 이 죽으면** 백엔드는 실행됐는데 UI 는 504 를 받을 수 있습니다 (회수 시점엔 대개 deadline 경과라 재실행은 안 됨). `reclaim-idle` 을 `request-timeout` 보다 짧게 하면 같은 요청이 다시 실행될 수 있으므로 백엔드 멱등성이 전제입니다 → [backend-guide §6](../../../../../../../docs/integration/backend-guide.md)
- 컨테이너에서 호스트의 백엔드는 `host.docker.internal`. Windows Git Bash 에서 `/` 로 시작하는 route 값은 `MSYS_NO_PATHCONV=1` → [dev-setup](../../../../../../../docs/dev-setup.md)
