# [rest api] UMD 범용 REST 프록시 (Server / Local 모드) — 개발 결과

| 항목 | 내용 |
|---|---|
| 계획 문서 | `01-development-plan.md` |
| 개발 가이드 | `ref/ui-development-guide.md`, `ref/backend-development-guide.md` |
| 아키텍처 | `note/architecture/umd-architecture.md` |
| 배포 문서 | `david94/note/process/deploy/umd.md` |
| 작성일 | 2026-10-25 |
| 상태 | 개발·테스트·컨테이너 검증 완료, **미커밋** (UMD / lib-core 되돌림) |

---

## 1. 결과 요약

- UMD 하나가 `umd.mode` 로 **server(AP-01)** / **local(Home PC)** / both(개발·테스트) 로 동작한다.
- server 는 UI 의 모든 요청(`/**`)을 Stream 으로, local 은 Stream 요청을 로컬 백엔드 REST 호출로 변환한다. 메서드·경로·쿼리·헤더·본문·상태코드를 그대로 나른다.
- UMD 는 업무를 모른다: 새 API 는 백엔드만 배포, 새 로컬 서비스는 route 한 줄 추가.
- **직결 전환 가능**: 같은 요청을 UMD 경유와 백엔드 직접 호출로 보냈을 때 상태·주요 헤더·본문이 같음을 테스트로 확인.
- eventNm 기반 태스크 게이트웨이(init)는 모두 제거.

---

## 2. 단계별 결과

### 1단계 — Stream 규격 (UMD 내부)

| 항목 | 결과 |
|---|---|
| lib-core 되돌림 | `spec/stream` 패키지, `StreamMessageTest`, `ApSystemList.UMD/HOME` 삭제 → lib-core 는 init 이전 상태 (테스트 28건 통과, 설치 jar 에 Stream 클래스 없음) |
| 새 규격 `tsh.d94.umd.common.spec` | `StreamKeys`, `StreamField`, `StreamFieldSupport`, `StreamHttpRequest`, `StreamHttpReply`, `StreamBodyCodec` |
| 본문 인코딩 | UTF-8 텍스트 계열은 utf8, 그 외(바이너리·다른 charset·Content-Type 없음)는 base64 로 무손실 |
| 함께 처리 | 빌드 유지를 위해 eventNm 태스크 계층(Controller/Service/Properties/Exception)과 테스트 2개를 이 단계에서 삭제 (계획상 5단계) |

### 2단계 — 모드 구조 / 공통

| 항목 | 결과 |
|---|---|
| 모드 | `UmdMode`, `UmdProperties`(server/local 하위 설정, 검증), `@ConditionalOnUmdMode` — 잘못된 모드는 기동 실패 |
| 패키지 | `common` / `server` / `local` 로 재배치, server 기능 7개에 모드 조건 |
| 공통 | `ProxyHeaderPolicy`, `StreamGroupInitializer`(Consumer Group 생성을 양 모드 공통으로) |
| 앞당김 | `LocalSecurityConfig` (local 앱 포트 전부 거부) — local 모드가 정상 기동하려면 필요 |
| 계획 변경 | `X-Forwarded-For` 는 덮어쓰지 않고 **이어 붙임** (Next.js 서버가 넣은 브라우저 IP 보존) |

### 3단계 — server 모드 `/**` 프록시

| 항목 | 결과 |
|---|---|
| 구현 방식 | **MVC 컨트롤러 대신 서블릿 필터**(`StreamProxyFilter`) + AsyncContext — MVC 는 CORS preflight 를 컨트롤러에 넘기지 않기 때문. `FormContentFilter` 비활성 |
| 사전 차단 (Redis 미적재) | 401 `UMD_UNAUTHORIZED`, 413 본문 초과, 503 대기 상한, 503 local 생존 신호 없음(즉시) |
| 대기 중 오류 | 504 시간 초과, 503 Redis 장애 / 종료 중, 502 응답 형식 오류(corrId 를 읽어 즉시) |
| 보안 | JWT 사전 확인, OPTIONS·`public-paths` 무토큰 통과, CORS 미처리, UMD 자체 오류에만 허용 Origin 최소 CORS |
| 정리 | context-path·Swagger·UMD CORS 설정·MVC 예외 처리기 제거, `UmdErrorCode` 재정의 |
| 추적 | corrId 를 `X-Trace-Id` 로 백엔드에도 전달 |
| 관측 | `umd_proxy_request_seconds`, `umd_proxy_rejected_total`, health `localAgent`(정보용) |

### 4단계 — local 모드

| 항목 | 결과 |
|---|---|
| 소비 | `RequestStreamConsumer` — 처리 여유만큼만 XREADGROUP, 가상 스레드 처리, Redis 장애 backoff, NOGROUP 재생성, 종료 시 처리 완료 대기 |
| 처리 | `LocalRequestProcessor` — 형식 오류·만료는 ACK 만, 라우팅 → 호출 → 응답 → XACK+XDEL, 응답 발행 실패 시 ACK 보류 |
| 라우팅 | `LocalRouteResolver` — path-prefix 최장 일치(경로 경계 단위), 경로 미변경 (Zuul 대체) |
| 호출 | `LocalHttpDispatcher` — JDK HttpClient, 남은 deadline 타임아웃, 리다이렉트 미추종, 연결 실패·본문 초과 502, 시간 초과 시 응답 없음 |
| 회수 | `PendingReclaimer` — XPENDING + XCLAIM, 회수분도 deadline 먼저 확인 (타임아웃 후 POST 중복 실행 방지) |
| 생존 신호 | `LocalHeartbeat` — 5초마다 SET EX 15, 정상 종료 시 자기 값만 삭제 |
| 관측 | `umd_local_dispatch_seconds` 외 4종, health `requestConsumer` |

### 5단계 — 정리

| 항목 | 결과 |
|---|---|
| 흔적 제거 | Home/eventNm/단계 메모 주석·이름 정리, `FakeHomeConsumer` → `FakeLocalConsumer`, 미사용 메서드 삭제, 미사용 import 0건 |
| lib-core 의존 축소 | `ApplicationProperties` 제거 → `/actuator/info` 로 이름·버전·환경·**모드·instance-id** 노출 |
| 설정 검사 | `UmdSettingsValidator` — `poll-timeout` ≥ Redis 명령 타임아웃이면 기동 실패 |

### 6단계 — 테스트

각 단계에서 함께 작성 (별도 단계 생략). 3장 참고.

### 7단계 — 배포 / 문서

| 항목 | 결과 |
|---|---|
| JWT 키 | `JwtService` 를 server 보안 설정에서만 Import → **local 은 JWT 키 불필요** |
| Redis 보안 | `REDIS_SSL_ENABLED`, ACL 사용자(`SPRING_DATA_REDIS_USERNAME`) 지원, ACL 예시는 배포 문서 |
| compose | `docker-compose.server.yml`(AP-01, 8081 공개, actuator 127.0.0.1) / `docker-compose.delap.yml`(Home PC, 앱 포트 미공개, routes 파일 마운트, `host.docker.internal`) |
| 설정 파일 | `.env.server.example`, `.env.delap.example`, `config/routes.example.yml` (실제 `config/routes.yml`, `.env.server` 는 git 제외) |
| 문서 | `umd-architecture.md` 전면 재작성, `deploy/umd.md` 두 모드 기준 재작성, 본 결과 문서 |

---

## 3. 테스트 결과

### 자동 테스트 — UMD 126건 전체 통과 (Docker 환경, Testcontainers Redis)

| 구분 | 테스트 클래스 | 건수 | 주요 검증 |
|---|---|---|---|
| 규격 | `StreamBodyCodecTest` | 19 | 인코딩 판정, 무손실 왕복 |
| 규격 | `StreamHttpMessageTest` | 12 | Map 왕복, 필수 필드·경로·상태 검증 |
| 공통 | `ProxyHeaderPolicyTest` | 7 | hop-by-hop·예약 헤더, X-Forwarded-For 체인 |
| 공통 | `UmdModeConditionTest` | 7 | 모드별 Bean, 잘못된 모드, 설정 바인딩·검증, poll-timeout 검사 |
| server | `StreamProxyServiceTest` | 10 | 헤더 처리, 그대로 전달, 사전 차단 3종, 오류 분류, 지표 |
| server | `PendingReplyRegistryTest` / `StreamRequestGatewayTest` / `ReplyStreamHealthIndicatorTest` | 5 / 2 / 3 | 대기 등록·타임아웃, 발행, health |
| server | `StreamRoundTripIntegrationTest` (Redis) | 3 | 왕복, 동시 20건, 타임아웃 |
| server | `ServerProxyIntegrationTest` (Redis+HTTP) | 17 | 메서드별 전달, 304·바이너리·404 그대로, preflight, 401·413·504·502·503, CORS, actuator |
| local | `LocalRouteResolverTest` / `LocalHttpDispatcherTest` / `LocalRequestProcessorTest` | 4 / 7 / 6 | 라우팅, 호출 결과별 처리, 처리 흐름 분기 |
| local | `PendingReclaimerIntegrationTest` (Redis) | 4 | XPENDING+XCLAIM 회수 |
| local | `LocalModeIntegrationTest` (Redis) | 6 | server Bean 없음, 앱 포트 403, health·info, 그룹 생성, 생존 신호, **JWT 키 없이 기동** |
| 종단 | `BothModeContextTest` | 1 | both 모드 Bean·보안 체인 1개 |
| 종단 | `EndToEndBothModeTest` (Redis+스텁 백엔드) | 13 | 전체 경로, route 2개, 502·504·본문 초과, preflight, Redis 잔여 없음, **직결 동등성** |

### 컨테이너 검증

| 검증 | 결과 |
|---|---|
| server 단독 (Redis) | health `healthy`, 무토큰 401, local 없음 503 즉시, 거절 지표 집계 |
| server + local + nginx (컨테이너 4개) | 둘 다 `healthy`, `GET /` 200 `text/html`, nginx 404 그대로, HEAD, 동시 20건 후 Stream·Pending 0, local 종료 2초·생존 신호 삭제·이후 즉시 503 |
| **compose 파일 그대로** (`docker-compose.server.yml` + `.local.yml`) | `config` 검증 통과, 둘 다 `healthy`, local 은 actuator(127.0.0.1)만 publish, JWT 키 없이 기동, server→Redis→local→nginx 200, 허용 Origin 401 에 CORS 헤더, info 의 mode 표시 |

### 개발 중 발견·수정한 문제

| 문제 | 원인 | 조치 |
|---|---|---|
| lib-core 설치 jar 에 지운 클래스가 남을 위험 | `clean` 없이 재설치 | `clean install` 후 jar 내용 확인 |
| 모드 분리 판정 실패 (테스트) | 랜덤 포트(0) 비교 | `ManagementPortType` 사용 (init 단계 동일 이슈 재사용) |
| local 기동 직후 health DOWN 10초 | Consumer Group 생성(ApplicationReady) 전에 소비 시작 → NOGROUP | 소비 루프 시작 시 그룹 보장 |
| 기동마다 "Consumer group 생성 실패" 경고 | BUSYGROUP 이 RedisSystemException 에 감싸여 판별 실패 (NOGROUP 감지도 동일) | 가장 안쪽 예외 메시지로 판별 |
| poll-timeout 설정 실수 시 읽기 상시 실패 위험 | BLOCK 시간 ≥ Redis 명령 타임아웃 | `UmdSettingsValidator` 로 기동 시 차단 |
| 컨테이너 검증에서 route `/` 가 `C:/Program Files/Git/` 로 변환 | Windows Git Bash 경로 변환 (UMD 문제 아님) | `MSYS_NO_PATHCONV=1`, 배포 문서에 주의 사항 기재 |
| 테스트 코드 오류 2건 | Pending 조회 API count 누락, 검증 테스트의 기본값 간섭 | 테스트 수정 |

---

## 4. 계획 대비 변경 사항

| 계획 | 실제 | 이유 |
|---|---|---|
| Stream 규격 lib-core | UMD 내부 (`common.spec`) | 사용처가 UMD 두 모드뿐 → lib-core 배포 의존 제거 |
| `@RequestMapping("/**")` 컨트롤러 | 서블릿 필터 + AsyncContext | CORS preflight 를 백엔드로 전달하기 위해 |
| `X-Forwarded-For` 덮어쓰기 | 체인에 이어 붙이기 | 브라우저 IP 보존 (표준) |
| `LocalSecurityConfig` 4단계 | 2단계 | 모드 구조에서 local 기동에 필요 |
| eventNm 코드 삭제 5단계 | 1단계 | lib-core 되돌림 후 빌드 유지 |
| 401 코드 없음 | `UMD_UNAUTHORIZED` | 다른 UMD 오류와 형식 통일 |
| 6단계 별도 | 각 단계에서 테스트 작성 | |
| (추가) | `/actuator/info` 모드 표시, `UmdSettingsValidator`, local JWT 키 불필요 | 운영 편의·안전 |

---

## 5. 남은 작업

| 항목 | 비고 |
|---|---|
| 커밋 | UMD 커밋. lib-core 는 init 이전 상태로 되돌려져 커밋할 변경 없음 |
| AP-02 Redis 보안 적용 | TLS, ACL 사용자(`umd-server`, `umd-local`), 방화벽 — 배포 문서 2장 |
| d94-web 프록시 라우트 정비 | PATCH/HEAD, 응답 그대로 전달, 헤더 전달, 35초 타임아웃 — `ref/ui-development-guide.md` 5장 |
| Local 백엔드 | 백엔드 개발 가이드 준수 (JWT 직접 검증, `forward-headers-strategy`, Trace ID 필터, actuator 포트 분리) |
| phase1 아키텍처 문서 보정 제안 | Zuul → Local UMD 라우팅(또는 Spring Cloud Gateway), Redis Insight 포트 15672 → 5540 |
| 후속 검토 | 사용자별 rate limit, 대용량 파일(presigned URL), Redis Sentinel(Phase 2) |
