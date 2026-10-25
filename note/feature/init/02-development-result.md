# [init] UMD 최초 개발 — 개발 결과

> **대체됨**: eventNm 기반 태스크 게이트웨이는 `note/feature/rest api` (범용 REST 프록시, server/local 모드)로 전면 교체되었다.
> 이 문서는 당시 기록으로 남긴다. 현재 구조는 `note/architecture/umd-architecture.md` 참고.

| 항목 | 내용 |
|---|---|
| 계획 문서 | `01-development-plan.md` |
| 아키텍처 | `note/architecture/umd-architecture.md` |
| 배포 문서 | `david94/note/process/deploy/umd.md` |
| 작성일 | 2026-10-25 |
| 상태 | 개발·테스트 완료, **미커밋** (lib-core / UMD) |

---

## 1. 단계별 개발 결과

### 0단계 — 골격 정리

| 항목 | 결과 | 파일 |
|---|---|---|
| DB 없이 기동 | DataSource / JPA / 기본 사용자 자동설정 제외, 스캔 범위 `tsh.d94.umd` 로 한정, lib-core 는 `JwtService`·`ApplicationProperties`·`SwaggerConfig` 만 Import | `UmdApplication` |
| 잘못된 설정 제거 | d94-query 패키지를 가리키던 `@EntityScan` / `@EnableJpaRepositories` 제거, `d94-lib-data` 의존성 제거 | `UmdApplication`, `pom.xml` |
| JWT 전용 보안 | DB 조회 없이 서명·만료만 검증, 401 EntryPoint, `/error` 공개 | `UmdSecurityConfig`, `UmdJwtAuthenticationFilter` |
| 설정 / 배포 파일 | port 8081, graceful shutdown, Redis, actuator, log4j2, Dockerfile, compose, `.env.example` | `application.yml` 외 |

### 1단계 — 메시지 규격 (lib-core)

| 항목 | 결과 | 파일 |
|---|---|---|
| Stream 키 | `d94:umd:req`, `d94:umd:rep:{instanceId}`, group `home-backend`, MAXLEN 상수 | `StreamKeys` |
| 요청 / 응답 | Map 변환(`toFields`/`fromFields`), 필수 필드 검증, `isExpired`, `toHead`, `replyBuilder` | `StreamRequest`, `StreamReply` |
| 기타 | 응답 상태 enum, 필드명 상수, `ApSystemList.UMD/HOME` 추가 | `StreamReplyStatus`, `StreamField`, `ApSystemList` |

### 2단계 — Stream 인프라

| 항목 | 결과 | 파일 |
|---|---|---|
| 요청 발행 | XADD `MAXLEN ~10000`, 기동 시 Consumer Group 생성(BUSYGROUP 무시, Redis 미연결 시 경고만) | `StreamRequestPublisher` |
| 응답 대기 | corrId → Future, 타임아웃 시 `TimeoutException` 후 자동 제거 | `PendingReplyRegistry` |
| 요청 진입점 | 발행 전 등록, 발행 실패 시 Future 즉시 실패, `StreamCall(corrId, Future)` 반환 | `StreamRequestGateway`, `StreamCall` |
| 응답 수신 | 기동 시각 이후만 읽기, 완료 후 XDEL, 늦은/잘못된 응답 처리, 종료 시 대기 요청 실패 + Stream 삭제 | `ReplyStreamListener` |
| Redis 장애 대응 | 읽기 실패 시 2초 backoff, 로그는 첫 실패와 30회마다 | `ReplyStreamListener` |

### 3단계 — HTTP API

| 항목 | 결과 | 파일 |
|---|---|---|
| 엔드포인트 | `POST /d94/umd/api/v1/tasks/{eventNm}`, `CompletableFuture` 비동기 응답 | `TaskGatewayController` |
| 중계 / 변환 | 화이트리스트 검증, 본문 → payload, 결과 → HTTP 상태(200/502/504/503), `X-Trace-Id` | `TaskGatewayService` |
| 오류 코드 | `UMD_UNSUPPORTED_EVENT` 등 9종 | `UmdErrorCode` |
| 예외 처리 | 잘못된 JSON 400, 없는 경로 404, 기타 500 | `UmdExceptionHandler` |
| 설정 | `umd.task.allowed-events: [HealthCheckReq]`, `spring.mvc.async.request-timeout: 60s` | `application.yml` |
| 보안 보완 | ASYNC 재디스패치 허용, CORS 에 `X-Trace-Id` 노출 | `UmdSecurityConfig` |

### 5단계 — 운영

| 항목 | 결과 | 파일 |
|---|---|---|
| 지표 | `umd_task_request_seconds{eventNm,outcome}`, `umd_stream_pending`, late / invalid / read error 카운터 | `UmdStreamMetrics` |
| health | `replyStream` — 리스너 동작 + 최근 10초 내 읽기 실패 없음 (Redis 복구 시 자동 UP) | `ReplyStreamHealthIndicator` |
| Stream 정리 | 응답 Stream TTL 1시간, 10분마다 갱신 | `ReplyStreamListener` |
| 종료 순서 | 리스너 phase 를 웹 서버보다 낮게 → 처리 중 요청 완료 후 정지 | `ReplyStreamListener` |

### 7단계 — 배포 준비

| 항목 | 결과 | 파일 |
|---|---|---|
| actuator 분리 | 8091 내부 포트, 분리된 경우에만 인증 없이 전체 endpoint 허용 (`ManagementPortType` 판정) | `application.yml`, `UmdSecurityConfig` |
| 이미지 | 비root 실행, HEALTHCHECK, `JAVA_OPTS`, `exec` 로 SIGTERM 수신 | `Dockerfile` |
| AP-01 compose | 8091 은 `127.0.0.1` 바인딩, `UMD_INSTANCE_ID=umd-ap01`, `stop_grace_period: 45s`, 로그 로테이션 | `docker-compose.yml`, `.env.example` |
| CI | PR: `mvn verify` / main·태그: 테스트 후 GHCR 푸시 | `.github/workflows/deploy.yml` |
| 문서 | 배포 절차, 환경 변수, 방화벽, 운영 확인 | `david94/note/process/deploy/umd.md` |

---

## 2. 테스트 결과

### 자동 테스트 — 전체 통과

| 저장소 | 테스트 클래스 | 건수 | 결과 |
|---|---|---|---|
| lib-core | `StreamMessageTest` | 8 | 통과 (lib-core 전체 36건 통과) |
| UMD | `PendingReplyRegistryTest` | 5 | 통과 |
| UMD | `StreamRequestGatewayTest` | 2 | 통과 |
| UMD | `TaskGatewayServiceTest` | 12 | 통과 |
| UMD | `ReplyStreamHealthIndicatorTest` | 3 | 통과 |
| UMD | `StreamRoundTripIntegrationTest` (Redis) | 3 | 통과 |
| UMD | `TaskGatewayHttpIntegrationTest` (Redis + HTTP) | 10 | 통과 |
| **UMD 합계** | | **35** | **전체 통과** |

### 수동 확인

| 항목 | 결과 |
|---|---|
| DB 없이 기동 | 약 6초 기동. 유효 토큰 → 인증 통과, 만료 토큰 / 토큰 없음 → 401 |
| Redis 미연결 기동 | 기동 지속, Consumer Group 생성 실패는 경고만. 8초간 재시도 경고 1줄 |
| 컨테이너 (Redis 연동) | Docker health `healthy`, 사용자 `app`, 8091 health `UP`, prometheus `umd_` 지표 출력, 8081 actuator·API 무토큰 401 |
| 컨테이너 종료 | `docker stop` 1초 내 graceful 종료, 응답 Stream 삭제 확인 |

### 개발 중 발견·수정한 문제

| 문제 | 원인 | 조치 |
|---|---|---|
| 없는 경로가 401 로 보임 | `/error` 디스패치까지 인증 요구 | `/error` 공개 |
| Redis 미연결 시 로그 폭주 (약 7ms 간격) | 리스너 poll 실패 즉시 재시도 | 2초 backoff + 로그 간격 제한 |
| 통합 테스트가 실행되지 않음 | Testcontainers 1.20.x 가 Docker 29 API 버전과 불일치 | `testcontainers.version` 1.21.4 로 상향 |
| 없는 경로가 500 | 전역 예외 처리기가 `NoResourceFoundException` 을 500 처리 | 404 + `UMD_NOT_FOUND` |
| health 가 Redis 복구 후에도 DOWN 유지 가능 | 실패 횟수가 응답 수신 시에만 초기화 | 마지막 실패 시각 기준(10초) 판정 |
| 테스트 환경에서 actuator 분리 판정 실패 | 랜덤 포트(0)끼리 비교 | `ManagementPortType` 사용 |

---

## 3. 남은 작업

| 항목 | 비고 |
|---|---|
| 커밋 / 배포 | lib-core 먼저 커밋·GitHub Packages 배포 → UMD 커밋 (UMD CI 가 신규 규격 필요) |
| Home PC Backend | XREADGROUP / XACK / `XAUTOCLAIM` 재처리 / deadline 확인 / 응답 MAXLEN |
| d94-web 연동 | `NEXT_PUBLIC_UMD_URL`, UMD 클라이언트 |
| 이벤트 확장 | 실제 업무 이벤트를 `ApMessageList` 와 `umd.task.allowed-events` 에 추가 |
| 운영 설정 | `NEXT_PUBLIC_URL` 실제 도메인, Redis `requirepass`, 로그 시각(UTC) 필요 시 `TZ` |
