# Backend 개발 가이드 — 통신 (UMD 경유 REST)

> 대상: Home PC 에서 실행되는 Local Backend (Spring Boot). UMD(local) 가 호출하는 REST 서비스의 통신 규칙.
> 관련: [architecture.md](../architecture.md), [ui-guide.md](ui-guide.md)

---

## 1. 핵심 원칙

**백엔드는 UMD 와 Redis 를 모른다.** 클라우드에 직접 띄울 평범한 REST 서비스로 개발한다.
나중에 서버로 올릴 때 **코드 변경 없이** 배포 위치와 UI 의 `BACKEND_URL` 만 바뀌어야 한다.

```
현재:  UI ─▶ UMD(server) ─Redis─ UMD(local) ──HTTP──▶ Backend (localhost)
미래:  UI ─────────────────────────────────────HTTP──▶ Backend (서버)
```

| 하라 | 하지 마라 |
|---|---|
| JWT 를 **백엔드가 직접 검증** | "UMD 가 검증했으니 믿는다" 는 가정 |
| 표준 헤더(`X-Forwarded-*`) 사용 | `X-Umd-*` 등 UMD 전용 헤더에 의존 |
| 상태코드를 REST 의미대로 반환 | 오류를 200 + 실패 플래그로 반환 |
| 30초 안에 응답 | 오래 걸리는 작업을 동기 응답으로 처리 |
| 요청 1MB / 응답 5MB 이하 | 대용량 업로드·다운로드·스트리밍 |

---

## 2. 경로와 라우팅

| 항목 | 규칙 |
|---|---|
| 경로 | `/v1/{resource}/{id}` (UI 의 `apiClient` 경로와 동일). context-path 를 쓰면 UI `BACKEND_URL` 에 포함 |
| UMD 경로 변환 | **없음.** UI 가 부른 경로가 그대로 도착한다 |
| 서비스가 여럿일 때 | 서비스마다 경로 prefix 를 겹치지 않게 정하고 Local UMD `umd.local.routes` 에 등록 |
| actuator | `management.server.port` 로 **별도 포트** 분리 — 앱 포트에 두면 UMD `/**` 를 통해 외부에서 닿는다 |
| Swagger | 앱 포트에 두되, 외부 공개가 필요하면 Server UMD `public-paths` 에 등록 (기본 비공개) |

```yaml
# Local UMD 설정 예 (Home PC)
umd:
  local:
    routes:
      - id: cira
        path-prefix: /v1/cira
        uri: http://localhost:8082
      - id: default
        path-prefix: /
        uri: http://localhost:8080
```

> 이 route 표는 미래에 서버의 Ingress / Gateway 규칙으로 그대로 옮겨진다.

---

## 3. 인증 · 인가

UMD 는 `Authorization: Bearer <JWT>` 를 **원본 그대로** 전달한다. 백엔드가 직접 검증한다.

| 항목 | 규칙 |
|---|---|
| 서명 키 | `JWT_SECRET_KEY` — d94-query(발급처)와 동일 값 |
| 검증 | 서명 + 만료. 실패 시 401 |
| 사용자 식별 | 토큰 `sub`(email) |
| 인가 | 역할·소유권 검사는 백엔드 책임 (403) |
| 구현 | lib-core `JwtService` 재사용. DB 가 있으면 lib-core `SecurityConfig` 그대로, DB 가 없으면 UMD 의 `UmdJwtAuthenticationFilter` 처럼 토큰만 검증 |
| 금지 | 사용자 정보를 헤더(`X-Auth-User` 등)로 받아 믿는 방식 — 직결 시 위조 가능 |

> UMD 도 JWT 를 확인하지만 Redis 앞 차단용일 뿐이다. 백엔드 검증을 생략하면 직결 전환 시 보안 구멍이 된다.

---

## 4. 헤더

### 4-1. 요청으로 받는 헤더

| 헤더 | 출처 | 사용법 |
|---|---|---|
| `Authorization` | UI (Next.js 서버) | JWT 검증 |
| `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host` | UMD(server) — 미래엔 로드밸런서 | `server.forward-headers-strategy: framework` 로 처리. 클라이언트 IP·절대 URL 생성에 사용 |
| `X-Trace-Id` | UMD(server) | 로그 MDC 에 넣고 응답에도 그대로 반환. **없으면 직접 생성** (직결 대비) |
| `Idempotency-Key` | UI (선택) | 중복 생성 방지 (6장) |
| `If-None-Match` / `If-Modified-Since` | UI | 조건부 GET (304) |
| `Host` | UMD(local) 가 route uri 기준으로 설정 | **쓰지 않는다.** 외부 주소는 `X-Forwarded-Host` 로 |
| `Cookie` | 전달되지 않음 | 쿠키 기반 인증·세션 사용 금지 |

```yaml
server:
  forward-headers-strategy: framework   # X-Forwarded-* 반영 (ServletUriComponentsBuilder 등)
```

### 4-2. 응답 헤더

- 백엔드가 설정한 헤더는 그대로 UI 까지 간다 (`Location`, `ETag`, `Cache-Control`, `Content-Disposition` 등).
- `Location` 은 상대 경로(`/v1/orders/43`) 또는 `ServletUriComponentsBuilder` 로 만든 절대 경로를 쓴다.

### 4-3. Trace ID 필터 예

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String traceId = Optional.ofNullable(req.getHeader("X-Trace-Id"))
            .filter(s -> !s.isBlank())
            .orElseGet(() -> UUID.randomUUID().toString());   // 직결 시 백엔드가 부여
        MDC.put("traceId", traceId);
        res.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

---

## 5. 응답 시간 · 크기

| 항목 | 한도 | 넘으면 |
|---|---|---|
| 처리 시간 | **30초** (UMD 타임아웃, 목표는 25초 이내) | UI 는 504. 백엔드가 늦게 끝내도 결과는 버려짐 |
| 요청 본문 | 1MB | UMD(server) 413 — 백엔드까지 오지 않음 |
| 응답 본문 | 5MB | UMD(local) 502 |
| 스트리밍 응답 | 불가 (`text/event-stream`, chunked 스트리밍, WebSocket) | 전체를 모아서 전달되거나 실패 |

**오래 걸리는 작업은 비동기 리소스로 설계한다.**

```
POST /v1/reports            → 202 Accepted, Location: /v1/reports/jobs/{jobId}
GET  /v1/reports/jobs/{id}  → 200 {status: RUNNING | DONE | FAILED, resultUrl}
```

**큰 데이터**

- 목록: 페이지네이션 필수 (`page`, `size`, 최대 size 제한)
- 파일: MinIO presigned URL 발급 API 로 브라우저가 직접 업·다운로드 (UMD 우회)

---

## 6. 멱등성 · 중복 실행

UMD(local) 는 장애 복구 시 미처리 요청을 다시 꺼낸다. **deadline 이 지나지 않았으면 같은 요청이 한 번 더 도착할 수 있다.**
이 규칙은 미래의 로드밸런서·클라이언트 재시도에도 그대로 필요하다.

| 메서드 | 요구 |
|---|---|
| GET / HEAD | 부수효과 없음 |
| PUT / DELETE | 멱등 (두 번 실행해도 결과 동일. 이미 삭제된 리소스 DELETE 는 204 또는 404) |
| POST (생성) | `Idempotency-Key` 헤더가 있으면 키별로 결과를 저장해 두 번째 요청에 같은 응답 반환 |
| PATCH | 가능하면 "값 설정" 형태 (증감 연산 지양) |

---

## 7. 응답 형식 · 상태코드

본문은 lib-core `ApiResponse` 를 사용한다. **상태코드는 REST 의미대로** 준다 (UMD 는 상태를 바꾸지 않고 그대로 전달).

| 상황 | 상태 |
|---|---|
| 조회 성공 | 200 |
| 생성 | 201 + `Location` |
| 비동기 접수 | 202 + `Location` |
| 본문 없는 성공 (삭제 등) | 204 |
| 조건부 GET 변경 없음 | 304 |
| 입력 검증 실패 | 400 (필드 오류 목록 포함) |
| 인증 실패 | 401 |
| 권한 없음 | 403 |
| 없음 | 404 |
| 상태 충돌 | 409 |
| 서버 오류 | 500 (Trace ID 로그) |

- **502 / 503 / 504 는 백엔드가 직접 쓰지 않는다.** UI 가 인프라 오류(UMD·Redis·로드밸런서)로 해석한다.
- 오류 본문에 스택 트레이스·내부 경로를 넣지 않는다.

---

## 8. CORS

- 현재 UI 는 Next.js 서버 프록시를 거쳐 호출하므로 브라우저 CORS 대상이 아니다.
- 브라우저 직접 호출이 생길 때를 대비해 **CORS 설정은 백엔드가 소유**한다 (UMD 는 OPTIONS 를 그대로 전달할 뿐 처리하지 않는다).
- 허용 origin 은 환경 변수(`NEXT_PUBLIC_URL` 등)로 받는다. lib-core `SecurityConfig` 의 CORS 설정 재사용 가능.

---

## 9. 로컬 실행 · 테스트

| 목적 | 방법 |
|---|---|
| 평소 개발 | 백엔드 단독 실행, HTTP 로 직접 호출 (UI `BACKEND_URL=http://localhost:8080`) |
| 단위·통합 테스트 | MockMvc / `@SpringBootTest` 로 **직접 호출만** 테스트. UMD 를 띄울 필요 없음 |
| UMD 경유 확인 | 로컬 Redis + UMD `UMD_MODE=both` + route 를 백엔드로 → UI `BACKEND_URL=http://localhost:8081` |
| Home PC 운영 | 백엔드 + UMD `UMD_MODE=local` (AP-02 Redis 에 아웃바운드 연결) |

UMD 는 투명하므로 **직접 호출에서 통과한 동작은 UMD 경유에서도 같아야 한다.** 다르면 UMD 버그로 보고한다.

- 백엔드 포트는 `localhost` 또는 로컬 docker 네트워크에만 바인딩한다 (인바운드 개방 금지가 이 구조의 목적).

---

## 10. 직결 전환 시 할 일 (미래)

UMD·Redis 를 빼고 백엔드를 서버에 띄울 때 **코드는 바꾸지 않는다.** 설정만 바뀐다.

| 항목 | 작업 |
|---|---|
| 배포 | 백엔드 이미지를 서버에 배포, 앞에 리버스 프록시·로드밸런서(TLS) 배치 |
| 라우팅 | Local UMD `routes` → Ingress / Gateway 규칙 |
| UI | `BACKEND_URL` 을 백엔드 주소로 변경 |
| 헤더 | 프록시가 `X-Forwarded-*` 설정 (백엔드는 이미 처리 중) |
| Trace ID | 백엔드 필터가 생성 (이미 구현됨) |
| 크기·시간 한도 | 필요 시 완화 (기존 코드는 영향 없음) |

---

## 11. 체크리스트

- [ ] 경로 `/v1/...`, 서비스 prefix 를 Local UMD route 에 등록
- [ ] JWT 직접 검증 (`JWT_SECRET_KEY` = d94-query), 헤더 기반 사용자 신뢰 없음
- [ ] `server.forward-headers-strategy: framework`
- [ ] `X-Trace-Id` 필터 (없으면 생성, MDC, 응답 헤더)
- [ ] actuator 별도 포트
- [ ] 상태코드 REST 의미대로, 502/503/504 직접 사용 안 함
- [ ] 30초 이내 응답, 장시간 작업은 202 + 작업 리소스
- [ ] 요청 1MB / 응답 5MB 이하, 목록 페이지네이션, 파일은 presigned URL
- [ ] PUT/DELETE 멱등, 중요 POST 는 `Idempotency-Key` 처리
- [ ] SSE·WebSocket·쿠키 세션 미사용
- [ ] 테스트는 직접 HTTP 호출로 작성 (UMD 불필요)
