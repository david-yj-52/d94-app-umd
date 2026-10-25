# [rest api] UMD 범용 REST 프록시 (Server / Local 모드) — 개발 계획

| 항목 | 내용 |
|---|---|
| 대상 | `d94-app-umd` (Stream 규격 포함). `d94-lib-core` 는 init 단계 변경분 되돌림만 |
| 기준 문서 | `david94/note/architecture/phase1-architecture.md` (Cloud UMD + Home PC UMD 구조) |
| 선행 작업 | `note/feature/init` (eventNm 기반 태스크 게이트웨이, 미커밋) |
| 개발 가이드 | `ref/ui-development-guide.md`, `ref/backend-development-guide.md` |
| 작성일 | 2026-10-25 |

---

## 1. 배경과 목표

Phase 1 아키텍처가 **Redis 양쪽에 UMD 를 두는 구조**로 바뀌었다.
init 단계의 eventNm 기반 게이트웨이를 버리고, UMD 를 **HTTP ↔ Redis Stream 양방향 변환기** 하나로 만든다.

```
                ┌──────── UMD (mode=server, AP-01) ────────┐               ┌──────── UMD (mode=local, Home PC) ─────────┐
UI ──HTTP──▶    │ /** 수신 → JWT 확인 → HTTP 를 Stream 으로 │ ──XADD──▶     │ XREADGROUP → Stream 을 HTTP 로 → 로컬 호출 │ ──HTTP──▶ Local REST Backend
   ◀──HTTP──    │ 응답 Stream 을 HTTP 응답으로 복원         │ ◀──XREAD──    │ HTTP 응답을 Stream 으로 → XADD → XACK      │ ◀──HTTP──
                └───────────────────────────────────────────┘    Redis      └────────────────────────────────────────────┘
                                                                (AP-02)
```

### 최종 목표: UMD·Redis 를 빼면 그대로 직결되는 구조

나중에 백엔드를 서버에 직접 띄울 때 **UMD 와 Redis 만 제거하고 UI 의 API 주소만 바꾸면** 동작해야 한다.

```
현재:  UI ──REST──▶ UMD(server) ─Redis─ UMD(local) ──REST──▶ Backend
미래:  UI ──REST──────────────────────────────────────────▶ Backend   (UI: NEXT_PUBLIC_API_URL 만 변경)
```

이를 위해 **UMD 는 투명해야 한다** — 백엔드·UI 가 UMD 의 존재에 기대는 지점이 없어야 한다.

| 투명성 조건 | 설계 |
|---|---|
| 경로가 같다 | UMD context-path 없음. UI 가 부르는 경로 = 백엔드가 받는 경로 (`/api/...`) |
| 인증은 백엔드가 한다 | `Authorization` 원본 전달, 백엔드가 JWT 직접 검증. UMD 의 JWT 확인은 Redis 앞 차단용(중복 검증) |
| CORS 는 백엔드가 한다 | UMD 는 CORS 를 처리하지 않고 OPTIONS 를 그대로 전달. (현재 d94-web 은 Next.js 서버 프록시 `/api/proxy` 경유로 호출하므로 일반 API 는 CORS 대상이 아님. 브라우저 직접 호출이 생길 때를 대비한 원칙) |
| 표준 헤더만 쓴다 | 백엔드는 `X-Forwarded-*`(표준)만 사용. `X-Umd-*` 등 UMD 전용 헤더에 의존 금지 |
| 오류는 상태코드로 | UI 는 502/503/504 를 일반 인프라 오류로 처리 (미래의 로드밸런서도 같은 코드를 냄) |

### 설계 원칙

| 원칙 | 내용 |
|---|---|
| **하나의 서비스, 두 모드** | 같은 jar/이미지를 `umd.mode` 로 `server` 또는 `local` 로 실행. 개발·테스트용 `both` 지원 |
| UMD 는 업무를 모른다 | 메서드·경로·쿼리·헤더·본문을 그대로 나르고 응답도 그대로 돌려준다. 새 API 추가 시 UMD 변경 없음 |
| 백엔드는 평범한 REST | Local Backend 는 Redis·UMD 를 모르는 일반 Spring REST 서비스 |
| Server 는 관문 | JWT 사전 확인, 본문 크기·대기 요청 상한, Local 생존 확인 |
| Local 은 라우터 | 경로 prefix → 로컬 서비스 URL 라우팅 (Zuul 역할 흡수) |
| eventNm 기반은 특수 상황용 | 현재 범위에서 제거 |

---

## 2. 범위

### 포함

1. Stream 규격을 **HTTP 요청/응답 표현**으로 재작성하고 **UMD 내부로 이동** (lib-core 변경분 되돌림)
2. 모드 전환 구조 (`server` / `local` / `both`) 와 공통 인프라 정리
3. **Server 모드**: catch-all(`/**`) 수신 → Stream 발행 → 응답 복원, 보호 장치, Local 생존 확인
4. **Local 모드**: Stream 소비 → 로컬 REST 호출(라우팅) → 응답 발행, 재처리, heartbeat
5. eventNm 관련 코드 제거, 모드별 보안·지표·health
6. 테스트 (`both` 모드 종단 테스트 포함)
7. 배포 구성 (AP-01 / Home PC), Redis 보안 설정, 문서 갱신
8. UI / Backend 개발 가이드 (`ref/`)

### 제외 (후속)

| 항목 | 이유 |
|---|---|
| 사용자별 rate limit (429) | 1차는 대기 요청 상한으로 보호 |
| 스트리밍 (SSE, WebSocket, 대용량 다운로드) | Request-Reply 구조와 맞지 않음. 알림 SSE 는 d94-query 유지 |
| 대용량 파일 업로드 | Redis 엔트리 크기 부담. MinIO presigned URL 별도 검토 |
| Redis Sentinel / 다중 Server UMD | Phase 2 |
| eventNm 기반 명령 모드 | 특수 조건 발생 시 별도 기능 |

---

## 3. Stream 규격

### 3-0. 위치: lib-core → UMD 내부

Stream 규격을 쓰는 곳은 UMD 의 Server·Local 두 모드뿐이다. 두 모드가 같은 코드베이스에 있으므로 **공유 라이브러리에 둘 이유가 없다.**

| 항목 | 결정 |
|---|---|
| 새 위치 | `d94-app-umd` 의 `tsh.d94.umd.common.spec` 패키지 |
| lib-core | init 단계에서 추가한 `tsh.d94.lib.core.spec.stream` 패키지, `StreamMessageTest`, `ApSystemList.UMD/HOME` 을 **전부 되돌림** (미커밋이라 비용 없음) |
| 효과 | 규격 변경 시 lib-core 배포 → UMD 빌드 순서가 사라짐. UMD 단독으로 빌드·배포 가능 |
| 재검토 시점 | UMD 가 아닌 다른 애플리케이션이 Stream 을 직접 읽고 쓰게 될 때 lib-core 로 다시 승격 |

### 3-1. 기존 규격 재검토 결과

| 클래스 / 항목 | 판단 | 이유 |
|---|---|---|
| `StreamKeys` | **유지 + 추가** | 요청/응답 Stream, Consumer Group, MAXLEN 유효. Local heartbeat 키 추가 |
| `StreamFieldSupport` | **유지 + 확장** | `requiredInt` 등 추가 |
| `StreamRequest` | **재작성** → `StreamHttpRequest` | `corrId`/`replyTo`/`ts`/`deadline`/`isExpired()` 유지. `eventNm`/`src`/`tgt`/`payload`/`toHead()` 제거, HTTP 필드 추가 |
| `StreamReply` | **재작성** → `StreamHttpReply` | `corrId`/`ts` 유지. `StreamReplyStatus`/`errCode`/`errMsg`/`payload` 제거, HTTP 상태·헤더·본문 추가 |
| `StreamReplyStatus` | **삭제** | OK/ERROR 는 HTTP 상태코드로 대체. EXPIRED 는 의미 없음 (deadline = Server 타임아웃이라 EXPIRED 가 도착할 때 Server 는 이미 504 응답 후) → Local 은 만료 요청을 응답 없이 XACK |
| `StreamField` | **재작성** | 필드 변경 |
| `ApSystemList.UMD/HOME`, `toHead()` | **되돌림** | 사용처 없음 (미커밋) |
| `StreamMessageTest` | **재작성** (UMD 테스트로 이동) | |
| **신규** `StreamBodyCodec` | 추가 | Content-Type 기준 utf8/base64 판정·변환 (Server·Local 공용) |

위 클래스는 모두 UMD `tsh.d94.umd.common.spec` 에 새로 만든다 (lib-core 에서 옮기는 것이 아니라, lib-core 쪽은 삭제).

### 3-2. 엔트리 형식 (flat String Map)

**요청** `d94:umd:req` (Consumer Group `home-backend`)

| 필드 | 예 | 비고 |
|---|---|---|
| `corrId` | `5f0c…` | 응답 매칭, `X-Trace-Id` |
| `replyTo` | `d94:umd:rep:umd-ap01` | Server 인스턴스별 응답 Stream |
| `method` | `GET` | GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS |
| `path` | `/api/v1/orders/42` | UI 가 부른 경로 그대로 (context-path 없음) |
| `query` | `page=1&size=20` | raw, 없으면 생략 |
| `headers` | `{"authorization":["Bearer …"],"content-type":["application/json"]}` | JSON 문자열(다중값), 허용 목록만 |
| `body`, `bodyEncoding` | `{"a":1}`, `utf8` | 텍스트 계열은 utf8, 그 외 base64. 없으면 생략 |
| `ts`, `deadline` | epoch millis | |

**응답** `d94:umd:rep:{serverInstanceId}`

| 필드 | 비고 |
|---|---|
| `corrId`, `ts` | |
| `status` | HTTP 상태코드 |
| `headers` | JSON 문자열 |
| `body`, `bodyEncoding` | 요청과 동일 규칙 |

**Local 생존 신호** `d94:umd:local:alive` — Local 이 5초마다 `SET … EX 15` (값: local instanceId, 시각)

---

## 4. 모드 구조

### 4-1. 설정

```yaml
server:
  port: 8081                        # context-path 없음 (경로 = 백엔드 경로)

umd:
  mode: ${UMD_MODE:server}          # server | local | both(개발·테스트 전용)
  instance-id: ${UMD_INSTANCE_ID:${HOSTNAME:umd-local}}

  server:                           # mode=server|both 일 때만 사용
    request-timeout: 30s
    max-request-body: 1MB
    max-pending: 500
    public-paths: []                # 무토큰 허용 경로 (예: 백엔드 Swagger). 기본 없음
    require-local-alive: true       # Local heartbeat 없으면 즉시 503

  local:                            # mode=local|both 일 때만 사용
    consumer-concurrency: 16        # 동시 처리 상한
    max-response-body: 5MB
    reclaim-idle: 60s               # 이 시간 이상 ACK 안 된 Pending 을 회수 (XPENDING + XCLAIM)
    reclaim-interval: 30s           # 회수 주기
    routes:                         # 긴 prefix 우선
      - id: cira
        path-prefix: /v1/cira
        uri: http://localhost:8082
      - id: default
        path-prefix: /
        uri: http://localhost:8080
```

### 4-2. 패키지와 Bean 활성화

`@ConditionalOnUmdMode(SERVER|LOCAL)` (내부적으로 `umd.mode` 검사) 로 모드별 Bean 만 생성한다.

```
tsh.d94.umd
├── common
│   ├── spec                        Stream 규격 (StreamKeys, StreamField, StreamHttpRequest,
│   │                               StreamHttpReply, StreamBodyCodec, StreamFieldSupport)
│   ├── UmdProperties               umd.mode / instance-id / server / local
│   ├── ConditionalOnUmdMode
│   ├── ProxyHeaderPolicy           hop-by-hop 제거 등 헤더 필터 (양 모드 공용)
│   ├── StreamGroupInitializer      Consumer Group 생성 (양 모드, 멱등)
│   └── UmdMetrics
├── server                          ── mode = server | both
│   ├── proxy.StreamProxyFilter     /* 서블릿 필터 (MVC 컨트롤러 대신 — 아래 5-1 참고), AsyncContext 비동기 응답
│   ├── proxy.StreamProxyService    ProxyRequest → Stream 요청, StreamHttpReply → ProxyResponse
│   ├── proxy.ProxyResponseWriter   응답 쓰기 + UMD 자체 오류에만 최소 CORS
│   ├── RequestPublisher            XADD 요청 (기존 StreamRequestPublisher)
│   ├── PendingReplyRegistry        (기존 유지)
│   ├── ReplyStreamListener         (기존 유지, 새 응답 파싱)
│   ├── LocalAliveChecker           heartbeat 키 확인 (1초 캐시)
│   ├── ServerSecurityConfig        JWT 사전 확인 + public-paths + OPTIONS 통과 (CORS 미처리)
│   └── UmdJwtAuthenticationFilter  (기존 유지)
└── local                           ── mode = local | both
    ├── RequestStreamConsumer       XREADGROUP → 동시 처리 → XACK + XDEL
    ├── LocalRouteResolver          path-prefix → uri (최장 일치)
    ├── LocalHttpDispatcher         RestClient 로 로컬 호출 (남은 deadline 을 타임아웃으로)
    ├── ReplyPublisher              XADD replyTo (MAXLEN ~1000)
    ├── PendingReclaimer            XAUTOCLAIM → deadline 확인 → 재처리 또는 XACK
    ├── LocalHeartbeat              생존 신호 갱신
    └── LocalSecurityConfig         앱 포트 전체 거부 (local 은 HTTP API 없음)
```

- `both` 모드는 한 프로세스에서 Server·Local 이 함께 동작 → 로컬 개발과 종단 테스트에 사용. 운영에서는 쓰지 않는다.
- actuator(8091) 는 두 모드 공통.

---

## 5. Server 모드 상세

### 5-1. 처리 흐름

```
요청 수신 (/**)
 ├─ OPTIONS (CORS preflight) → JWT 검사 없이 전달 (CORS 응답은 백엔드가 생성)
 ├─ public-paths 외 JWT 확인 실패 → 401          (Redis 미적재)
 ├─ 본문 > max-request-body → 413                (Redis 미적재)
 ├─ 대기 요청 ≥ max-pending → 503 OVERLOADED     (Redis 미적재)
 ├─ Local heartbeat 없음 → 503 LOCAL_UNAVAILABLE (Redis 미적재, 30초 대기 없이 즉시)
 └─ register(corrId) → XADD → 비동기 대기
       ├─ 응답 → status/headers/body 그대로 + X-Trace-Id
       └─ 타임아웃 → 504
```

- **구현: `@RequestMapping("/**")` 컨트롤러가 아니라 서블릿 필터(`StreamProxyFilter`)** 로 처리한다.
  Spring MVC 는 CORS preflight 를 핸들러에 넘기지 않고 직접 응답하므로, preflight 를 백엔드로 전달하려면 MVC 앞에서 받아야 한다.
  (FormContentFilter 도 PUT/PATCH form 본문을 미리 읽으므로 비활성화)
- UMD 의 JWT 확인은 **Redis 앞 차단용**이다. 최종 인증·인가는 백엔드가 같은 토큰으로 다시 검증한다.
- UMD 가 401 을 낼 때도 CORS 헤더가 없으면 브라우저가 상태를 읽지 못한다 → **UMD 자체 오류 응답에 한해** 요청 `Origin` 이 `umd.server.cors-origins`(백엔드와 같은 값)에 있으면 최소 CORS 헤더를 붙인다. 정상 경로의 CORS 는 전부 백엔드 몫.

### 5-2. 헤더 정책 (UI → Stream)

| 구분 | 내용 |
|---|---|
| 전달 | `Authorization`, `Content-Type`, `Accept`, `Accept-Language`, `Origin`, `Access-Control-Request-*`, `If-None-Match`, `If-Modified-Since`, `Idempotency-Key`, `X-*`(아래 예약 헤더 제외) |
| 제외 | hop-by-hop(`Connection`, `Keep-Alive`, `Transfer-Encoding`, `TE`, `Upgrade`), `Host`, `Content-Length`, `Cookie` |
| Server 가 설정 (UI 값 덮어씀) | `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Trace-Id` |
| Server 가 이어 붙임 | `X-Forwarded-For` — 앞단(Next.js 서버)이 보낸 체인 뒤에 직전 홉 주소 추가 (표준 동작, 브라우저 IP 보존). 체인 앞부분은 신뢰 구간 밖이므로 백엔드는 IP 를 인증 근거로 쓰지 않는다 |

> `Authorization` 을 전달하므로 토큰이 Redis 를 지난다. 위험 완화: access token 15분 수명, Local 이 처리 직후 요청 엔트리 XDEL, Redis TLS·ACL (7-1).

### 5-3. Server 자체 오류 응답

Local 이 돌려준 응답은 손대지 않는다. Server 가 만든 응답만 `ApiResponse` + `X-Umd-Error` 헤더 (진단용, UI·백엔드는 의존하지 않음).

| 상황 | HTTP | 코드 |
|---|---|---|
| 토큰 없음/무효 | 401 | `UMD_UNAUTHORIZED` |
| 요청 본문 초과 | 413 | `UMD_PAYLOAD_TOO_LARGE` |
| 대기 요청 상한 초과 | 503 | `UMD_OVERLOADED` |
| Local 미기동 (heartbeat 없음) | 503 | `UMD_LOCAL_UNAVAILABLE` |
| Redis 장애 | 503 | `UMD_REDIS_UNAVAILABLE` |
| Server 종료 중 | 503 | `UMD_SHUTTING_DOWN` |
| 응답 시간 초과 | 504 | `UMD_TIMEOUT` |
| 응답 형식 오류 | 502 | `UMD_BAD_REPLY` |

---

## 6. Local 모드 상세

### 6-1. 처리 흐름

```
XREADGROUP home-backend {instanceId} COUNT n BLOCK 2s   (동시 처리 여유만큼만 읽음)
 └─ 엔트리마다 (가상 스레드, 최대 consumer-concurrency)
     ├─ 형식 오류 → XACK + XDEL + 로그 (응답 불가: replyTo 를 모를 수 있음)
     ├─ now > deadline → XACK + XDEL (응답 없음, 지표만)
     ├─ 경로 → route 매칭 실패 → 404 UMD_NO_ROUTE 응답
     ├─ RestClient 호출 (method/path/query/headers/body, 타임아웃 = deadline - now)
     │    ├─ 성공 → status/headers/body 그대로
     │    ├─ 연결 실패 → 502 UMD_LOCAL_BACKEND_UNAVAILABLE
     │    ├─ 시간 초과 → 응답 없음 (Server 가 이미 504)
     │    └─ 응답 본문 > max-response-body → 502 UMD_RESPONSE_TOO_LARGE
     ├─ XADD replyTo (MAXLEN ~1000)
     └─ XACK + XDEL   (토큰·본문이 담긴 요청 엔트리를 Redis 에 남기지 않음)
```

### 6-2. 신뢰성

| 상황 | 동작 |
|---|---|
| Local 재시작 | 처리 중이던 엔트리는 Pending 에 남음 → `PendingReclaimer` 가 `reclaim-idle` 이후 회수 |
| 회수한 엔트리 | **deadline 먼저 확인** → 대부분 만료라 XACK+XDEL. 만료 전이면 재처리 (POST 중복 실행 방지의 핵심) |
| 로컬 백엔드 4xx/5xx | 정상 응답으로 그대로 전달 (UMD 오류 아님) |
| 동시성 | 처리 중 건수 < `consumer-concurrency` 일 때만 추가로 읽음 → 백엔드 과부하 방지 |
| 순서 | 보장하지 않음 (REST 요청은 독립적) |

### 6-3. 헤더 정책 (Stream → 로컬 백엔드 / 응답)

| 방향 | 내용 |
|---|---|
| 로컬 호출 | Stream 의 헤더 그대로 (`Authorization`, `X-Forwarded-*` 포함). `Host` 는 route uri 기준 |
| 응답 → Stream | hop-by-hop, `Content-Length` 제외, 나머지 그대로 (`Location`, `ETag`, `Cache-Control`, `Access-Control-*` 등) |

### 6-4. 라우팅 (Zuul 대체)

- `routes` 의 `path-prefix` 최장 일치 → `uri` 로 호출. **경로는 바꾸지 않는다** (미래 직결 시 경로 동일 유지).
- Spring Cloud Netflix Zuul 은 Spring Boot 3 와 호환되지 않으므로 사용하지 않는다.
- 이 route 설정은 미래에 서버의 Ingress / Gateway 라우팅 규칙으로 그대로 옮긴다.

---

## 7. 공통 사항

### 7-1. Redis 보안 (Local 이 인터넷 너머에서 접속)

| 항목 | 내용 |
|---|---|
| 전송 | Redis TLS (`spring.data.redis.ssl.enabled=true`) 또는 WireGuard/SSH 터널 내부 접속 |
| 인증 | Redis ACL 사용자 분리 — `umd-server`: 요청 XADD, 자기 응답 Stream 읽기·삭제, heartbeat GET / `umd-local`: XREADGROUP·XACK·XDEL·XAUTOCLAIM, 응답 XADD, heartbeat SET. 키 패턴 `~d94:umd:*` |
| 데이터 | 요청 엔트리는 처리 직후 XDEL, 응답은 수신 직후 XDEL, 요청 MAXLEN ~10000 |
| 설정 | `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` 환경 변수 추가 |

### 7-2. 지표

| 모드 | 지표 |
|---|---|
| server | `umd_proxy_request_seconds{method,status,outcome}`, `umd_stream_pending`, `umd_proxy_rejected_total{reason}`, 기존 late/invalid/read error |
| local | `umd_local_dispatch_seconds{method,status,route,outcome}`, `umd_local_inflight`, `umd_local_expired_total`, `umd_local_reclaimed_total` |

- `status` 는 `2xx`/`4xx` 등 클래스, `route` 는 설정의 route id → 값 종류가 고정. 경로는 태그로 쓰지 않는다.

### 7-3. Health

| 모드 | 항목 |
|---|---|
| server | `redis`, `replyStream`(기존), `localAgent`(heartbeat 유무, **상태에 반영하지 않고 정보만**) |
| local | `redis`, `requestConsumer`(소비 루프 동작, 최근 읽기 실패 없음) |

### 7-4. 보안 설정

| 모드 | 앱 포트 |
|---|---|
| server | JWT 사전 확인 (`public-paths`, OPTIONS 제외). CORS·Swagger 처리 안 함 |
| local | 모든 요청 거부 (HTTP API 없음). 앱 포트를 publish 하지 않음 |

---

## 8. 코드 정리 (init 대비)

| 구분 | 대상 |
|---|---|
| 삭제 | `TaskGatewayController`, `TaskGatewayService`, `UmdTaskProperties`, `UmdTaskException`, `umd.task.*`, springdoc 설정, `server.servlet.context-path`, UMD CORS 설정 |
| 이동·수정 | `infra.stream.*` → `server.*` (Gateway·Listener·Registry·Publisher), `UmdStreamProperties` → `UmdProperties`, `UmdSecurityConfig` → `ServerSecurityConfig`, `UmdErrorCode` 재정의 |
| 유지 | actuator 포트 분리, graceful shutdown 순서, 응답 Stream TTL, Dockerfile |
| 신규 | `common.*`, `server.StreamProxy*`, `server.LocalAliveChecker`, `local.*` 전체 |

---

## 9. 단계별 작업

| 단계 | 작업 | 산출물 |
|---|---|---|
| 1 | Stream 규격 (UMD 내부) | lib-core `spec/stream`·`StreamMessageTest`·`ApSystemList` 변경 되돌림 → UMD `common.spec` 에 `StreamKeys`, `StreamField`, `StreamFieldSupport`, `StreamHttpRequest`, `StreamHttpReply`, `StreamBodyCodec`(heartbeat 키 포함) 작성, 단위 테스트 |
| 2 | 모드 구조 / 공통 | `UmdProperties`, `ConditionalOnUmdMode`, 패키지 재배치, `ProxyHeaderPolicy`, `StreamGroupInitializer` |
| 3 | Server 모드 | `/**` 프록시, 보호 장치(413/503), `LocalAliveChecker`, 오류 응답, 보안 |
| 4 | Local 모드 | Consumer, 라우팅, Dispatcher, ReplyPublisher, Reclaimer, Heartbeat, 보안 |
| 5 | 정리 | eventNm 코드 삭제, 모드별 지표·health |
| 6 | 테스트 | 10장 |
| 7 | 배포 / 문서 | AP-01·Home compose, Redis TLS·ACL 설정 가이드, `umd-architecture.md`, `deploy/umd.md`, `phase1-architecture.md` 보정 제안(Zuul, Redis Insight 포트) |

---

## 10. 테스트 대상

### 단위

| 대상 | 검증 |
|---|---|
| `StreamHttpRequest` / `StreamHttpReply` / `StreamBodyCodec` | Map 왕복, 필수 필드 오류, 헤더 다중값, utf8/base64 판정·왕복, deadline 판정 |
| `ProxyHeaderPolicy` | hop-by-hop·Host·Cookie 제외, `Authorization` 전달, `X-Forwarded-*`·`X-Trace-Id` 덮어쓰기 |
| `StreamProxyService` (server) | 경로 그대로 전달, 쿼리 전달, 응답 그대로 복원, 413 / 503(OVERLOADED, LOCAL_UNAVAILABLE) / 504 / 502 |
| `LocalRouteResolver` | 최장 prefix 일치, 매칭 없음, 경로 미변경 |
| `LocalHttpDispatcher` | 남은 deadline 타임아웃, 연결 실패 502, 응답 본문 초과 502 |
| `PendingReclaimer` | 만료 엔트리 XACK 만, 미만료 재처리 |
| 모드 활성화 | `server` 에 local Bean 없음, `local` 에 server Bean·프록시 엔드포인트 없음 |

### 통합 (Testcontainers Redis + 스텁 로컬 백엔드, `mode=both`)

스텁 백엔드는 JDK `HttpServer` 로 띄워 의존성을 늘리지 않는다.

| 시나리오 | 기대 |
|---|---|
| `GET /api/v1/items?page=2` | 200, 백엔드가 받은 경로(`/api/v1/items`)·쿼리 일치, 본문 그대로 |
| `POST` JSON | 201 + `Location` 그대로 |
| `PUT` / `PATCH` / `DELETE` | 메서드 그대로, 204 본문 없음 |
| 백엔드 400(검증 오류 본문) / 404 / 401 / 403 | 상태·본문 그대로 (UMD 가 감싸지 않음) |
| `If-None-Match` → 304 | 조건부 헤더 전달, 304 그대로 |
| 바이너리 응답 (`image/png`) | 바이트 일치 |
| 두 route | prefix 별로 다른 백엔드 호출 |
| 백엔드 다운 | 502 `UMD_LOCAL_BACKEND_UNAVAILABLE` |
| 백엔드 지연 > 타임아웃 | 504, Local 은 응답을 보내지 않음 |
| Local 비활성 (`mode=server` 컨텍스트) | 503 `UMD_LOCAL_UNAVAILABLE` 즉시 |
| Pending 회수 | Local 처리 중 중단 → 재기동 후 만료 엔트리 XACK, 백엔드 재호출 없음 |
| 토큰 없음 / 본문 초과 / 대기 상한 | 401 / 413 / 503, Redis 미적재 |
| `Authorization` | 백엔드가 원본 토큰을 받음. 처리 후 요청 Stream 에 엔트리가 남지 않음 |
| UI 가 `X-Forwarded-Proto`·`X-Trace-Id` 위조 | 백엔드는 Server 가 설정한 값을 받음 |
| `X-Forwarded-For` | 앞단 체인 뒤에 Server 가 직전 홉 주소를 이어 붙인 값 |
| OPTIONS preflight | 토큰 없이 백엔드까지 전달, 백엔드의 `Access-Control-*` 헤더가 UI 에 그대로 |
| UMD 401 응답 | 허용 Origin 이면 CORS 헤더 포함 (브라우저가 401 을 읽을 수 있음) |
| actuator | 8091 정상, 앱 포트 `/actuator/**` 는 일반 경로로 취급 (route 없으면 404) |
| **직결 동등성** | 같은 스텁 백엔드를 UMD 경유 / 직접 호출했을 때 상태·주요 헤더·본문이 같음 |

---

## 11. 배포 구성

| 항목 | AP-01 (server) | Home PC (local) |
|---|---|---|
| `UMD_MODE` | `server` | `local` |
| `UMD_INSTANCE_ID` | `umd-ap01` | `umd-home01` (Consumer 이름) |
| 앱 포트 | 8081 공개 | publish 안 함 |
| actuator | 127.0.0.1:8091 | 127.0.0.1:8091 |
| 필수 env | `REDIS_*`, `JWT_SECRET_KEY`, `UMD_CORS_ORIGINS` | `REDIS_*`, `UMD_LOCAL_ROUTES`(또는 설정 파일 마운트) |
| 네트워크 | 인바운드 8081 | 아웃바운드 Redis 만, 로컬 백엔드는 같은 docker 네트워크 또는 `host.docker.internal` |

이미지는 하나(`ghcr.io/david-yj-52/d94-app-umd`), compose 파일만 `docker-compose.server.yml` / `docker-compose.local.yml` 로 나눈다.

---

## 12. 결정 사항

| # | 항목 | 결정 |
|---|---|---|
| 1 | 인증 | `Authorization` 원본 전달, **백엔드가 JWT 직접 검증**. UMD 는 사전 차단용으로만 확인 |
| 2 | 경로 | UMD context-path 없음. UI 경로 = 백엔드 경로. 전달 범위 `/**` |
| 3 | CORS | **백엔드가 담당**. UMD 는 OPTIONS 통과, 자체 오류 응답에만 최소 CORS 헤더 |
| 4 | 본문 상한 | 요청 1MB / 응답 5MB |
| 5 | Local 라우팅 | Local UMD 가 직접 (Zuul 제거), 경로 미변경 |
| 6 | Redis 접속 보안 | TLS + ACL (대안: WireGuard/SSH 터널) |
| 7 | Local 미기동 시 | 즉시 503 (heartbeat) |
| 8 | rate limit | 이번 범위 제외 |
| 9 | `Cookie` | 전달하지 않음. 쿠키 기반 인증이 필요해지면 재검토 |

---

## 13. 완료 기준

- UMD 전체 테스트 통과 (`both` 모드 종단 테스트, 직결 동등성 테스트 포함), lib-core 는 init 이전 상태로 복원되어 기존 테스트 통과
- UMD 가 lib-core 의 Stream 관련 코드 없이 빌드됨
- 같은 이미지로 `server` / `local` 모드 각각 기동 확인
- UMD 코드에 업무 경로·eventNm 의존 없음 (새 API 추가 시 UMD 변경·재배포 불필요, 새 로컬 서비스는 route 설정만 추가)
- 백엔드가 UMD 없이 직접 호출되어도 코드 변경 없이 동작 (개발 가이드 준수)
- 아키텍처·배포 문서, UI·Backend 개발 가이드 작성
