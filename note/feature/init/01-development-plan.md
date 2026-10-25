# [init] UMD 최초 개발 — 개발 계획

| 항목 | 내용 |
|---|---|
| 대상 | `d94-app-umd` (UI Mediator, AP-01) + `d94-lib-core` (공유 메시지 규격) |
| 목표 | UI HTTP 요청을 Redis Stream 으로 Home PC 에 중계하고 결과를 HTTP 로 응답 |
| 기준 문서 | `david94/note/architecture/phase1-architecture.md` |
| 작성일 | 2026-10-25 |

---

## 1. 범위

### 포함

- UMD 골격 정리 (DB 미사용 기동, JWT 검증 전용 보안)
- UMD ↔ Home 간 Redis Stream 메시지 규격 (lib-core)
- Redis Stream 요청 발행 / 응답 수신 인프라
- UI 용 HTTP 태스크 게이트웨이 API
- 운영: health, 지표, graceful shutdown, Stream 정리
- AP-01 배포 준비: Dockerfile, docker-compose, CI, 배포 문서

### 제외 (후속)

- Home PC Backend 구현 (XREADGROUP / XACK / 재처리) — 규격만 정의
- d94-web(UI) 의 UMD 클라이언트
- 장시간 작업용 비동기 모드 (202 + jobId)
- Phase2 다중 Mediator / HAProxy

### 결정 사항

| 결정 | 이유 |
|---|---|
| UMD 는 DB 없이 JWT 서명/만료만 검증 | Mediator 는 무상태로 두고, 사용자 조회는 토큰 발급처(d94-query) 책임 |
| 응답 Stream 을 인스턴스별로 분리 | 다중 Mediator 확장 시 응답 혼선 방지 |
| 메시지 규격은 lib-core 에 둔다 | Home PC Backend 와 공유 |
| Stream 엔트리는 flat String Map | Redis Insight 가독성, 언어 독립적 |

---

## 2. 단계별 기능 리스트

| 단계 | 기능 | 세부 항목 |
|---|---|---|
| 0 | 골격 정리 | `@EntityScan` 오류 수정, DB 자동설정 제외, lib-core 필요한 Bean 만 Import, `d94-lib-data` 제거, JWT 전용 Security, `application.yml` / log4j2 / Dockerfile / compose |
| 1 | 메시지 규격 (lib-core) | `StreamKeys`, `StreamField`, `StreamRequest`, `StreamReply`, `StreamReplyStatus`, `ApSystemList.UMD/HOME` |
| 2 | Stream 인프라 | `StreamRequestPublisher`(XADD MAXLEN, Consumer Group 생성), `PendingReplyRegistry`, `StreamRequestGateway`, `ReplyStreamListener`(수신·XDEL·종료 처리), Redis 장애 시 backoff |
| 3 | HTTP API | `POST /api/v1/tasks/{eventNm}`, 이벤트 화이트리스트, 결과 → HTTP 상태 매핑, `X-Trace-Id`, 비동기 MVC, 예외 처리 |
| 4 | 보안 | JWT 검증, CORS, 비동기 재디스패치 허용 (0·3단계에서 함께 처리) |
| 5 | 운영 | Micrometer 지표, `replyStream` health, 응답 Stream TTL 갱신, graceful shutdown 순서 |
| 6 | 테스트 | 단위 + Testcontainers Redis 통합 + HTTP 종단 테스트 (각 단계에서 함께 작성) |
| 7 | 배포 준비 | actuator 내부 포트 분리, Dockerfile 강화, AP-01 compose, GitHub Actions, 배포 문서 |

---

## 3. 테스트 대상

### 단위 테스트

| 대상 | 검증 항목 |
|---|---|
| `StreamRequest` / `StreamReply` (lib-core) | Map 변환 왕복, 필수 필드 누락·잘못된 이벤트 오류, deadline 만료 판정, replyBuilder src/tgt 반전, traceId 매핑 |
| `PendingReplyRegistry` | 응답 완료, 타임아웃, 늦은 응답 무시, 중복 corrId, 전체 실패 처리 |
| `StreamRequestGateway` | 요청 필드 구성(src/tgt/replyTo/ts/deadline), 발행 실패 시 Future 실패 + 정리 |
| `TaskGatewayService` | 결과별 HTTP 상태·코드 매핑, payload 전달/파싱, 이벤트 화이트리스트, 지표 outcome 태그 |
| `ReplyStreamHealthIndicator` | 리스너 상태·읽기 실패에 따른 UP/DOWN |

### 통합 테스트 (Testcontainers Redis + 가짜 Home Consumer)

| 대상 | 검증 항목 |
|---|---|
| Stream 왕복 | 정상 왕복 + 응답 XDEL, 동시 요청 20건 corrId 매칭, 무응답 타임아웃 |
| HTTP 종단 | 200 / 502 / 504 / 401 / 400(이벤트·JSON) / 404, actuator 포트 health·prometheus, API 포트에서 actuator 미노출 |

### 수동 확인

| 대상 | 검증 항목 |
|---|---|
| DB 없이 기동 | 기동 성공, 토큰 유효/만료/없음 응답 |
| Redis 미연결 기동 | 기동 지속, 재시도 로그 폭주 없음 |
| 컨테이너 | Docker health `healthy`, 비root 실행, 포트별 접근 제어, `docker stop` graceful 종료 + 응답 Stream 삭제 |

---

## 4. 완료 기준

- UMD·lib-core 전체 테스트 통과 (Docker 환경에서 통합 테스트 포함)
- 로컬 컨테이너로 AP-01 배포 형태 기동 확인
- 아키텍처 / 배포 문서 작성
