# UI 개발 가이드 — 통신 (UMD 경유 REST)

> 대상: `d94-web` (Next.js). UMD 를 거쳐 Local 백엔드를 호출하는 API 개발 규칙.
> 관련: [architecture.md](../architecture.md), [backend-guide.md](backend-guide.md)

---

## 1. 핵심 원칙

**UI 는 UMD 의 존재를 모른다.** 평범한 REST 백엔드를 호출하듯 개발하고, 백엔드가 서버로 올라가는 날에는 **환경 변수 하나만** 바꾼다.

```
현재:  Browser ─▶ Next.js /api/proxy ─(BACKEND_URL)─▶ UMD(server) ─Redis─ UMD(local) ─▶ Backend
미래:  Browser ─▶ Next.js /api/proxy ─(BACKEND_URL)─────────────────────────────────▶ Backend
```

| 하라 | 하지 마라 |
|---|---|
| 상태코드로 성공/실패 판단 | UMD 오류 코드(`UMD_*`)나 `X-Umd-Error` 헤더로 분기 |
| 백엔드 경로(`/v1/...`)만 사용 | UMD 전용 경로(`/actuator` 등) 호출 |
| 요청은 작게, 응답은 페이지 단위로 | 1MB 넘는 요청 / 5MB 넘는 응답 |
| 실패 시 `X-Trace-Id` 를 로그·오류 화면에 남김 | `X-Forwarded-*` 등 예약 헤더를 직접 설정 |

---

## 2. 호출 구조 (현재 d94-web 기준)

| 구성 | 역할 |
|---|---|
| `src/lib/api/client.ts` (`apiClient`) | 브라우저 → `/api/proxy/v1/...` (같은 origin, CORS 없음) |
| `src/app/api/proxy/[...path]/route.ts` | Next.js 서버에서 세션 토큰을 붙여 `${BACKEND_URL}/v1/...` 호출 |
| `src/lib/api/{domain}.ts` | 도메인별 API 함수 (`apiClient` 만 사용) |

- 브라우저는 UMD 를 직접 부르지 않는다 → **일반 API 는 CORS 를 신경 쓰지 않아도 된다.**
- 토큰은 Next.js 서버가 `Authorization: Bearer` 로 붙인다 (next-auth 세션의 `accessToken`, d94-query 발급).

### 환경 변수

| 변수 | 현재 | 미래 (백엔드 서버 배포) |
|---|---|---|
| `BACKEND_URL` | UMD(server) 주소 — 예 `https://ap01.example.com:8081` | 백엔드 주소 |
| `NEXT_PUBLIC_BACKEND_URL` | 알림 SSE 등 **브라우저가 직접** 붙는 d94-query 주소 (UMD 경유 아님) | 변경 없음 |

> UMD 는 context-path 가 없다. 백엔드에 context-path 가 있다면(예 `/tsh`) `BACKEND_URL` 에 포함한다. 경로는 UMD 를 지나도 바뀌지 않는다.

---

## 3. 요청 작성 규칙

| 항목 | 규칙 |
|---|---|
| 메서드 | REST 의미대로: 조회 GET, 생성 POST, 전체 수정 PUT, 부분 수정 PATCH, 삭제 DELETE |
| 경로 | `/v1/{resource}/{id}` 형태. 동사형 경로(`/v1/doSomething`) 지양 |
| 조회 조건 | 쿼리 파라미터 (`?page=0&size=20&status=OPEN`) |
| 본문 | JSON, **1MB 이하** (초과 시 UMD 가 413) |
| 파일 업로드 | 1MB 이하만 multipart 로. 큰 파일은 presigned URL 업로드 방식으로 별도 설계 |
| 목록 | 반드시 페이지네이션 (`PageResponse<T>`). 응답 **5MB 이하** |
| 중복 방지 | 결제·생성처럼 두 번 실행되면 안 되는 POST 는 `Idempotency-Key: <uuid>` 헤더 전송 (백엔드와 합의) |
| 스트리밍 | SSE·WebSocket·스트리밍 다운로드는 UMD 경유 불가. 필요하면 별도 설계 (알림은 d94-query SSE 사용) |

---

## 4. 응답 / 오류 처리

### 4-1. 상태코드별 처리

| 상태 | 의미 | UI 처리 |
|---|---|---|
| 2xx | 성공 | `res.data.data` 사용 (`ApiResponse`). 204 는 본문 없음 |
| 304 | 변경 없음 (ETag) | 캐시 데이터 사용 |
| 400 / 422 | 입력 오류 | 백엔드 메시지를 폼에 표시 |
| 401 | 토큰 없음·만료 | 재로그인 (`apiClient` 가 `signOut`) |
| 403 | 권한 없음 | 권한 안내 |
| 404 | 없음 | 빈 상태 / 안내 |
| 409 | 충돌 | 새로고침 유도 |
| 413 | 요청이 너무 큼 | 크기 제한 안내 |
| 502 / 503 / 504 | **인프라 오류** (UMD, Redis, 집 PC, 미래의 로드밸런서) | "일시적으로 연결할 수 없음" 안내 + 재시도 버튼 |
| 그 외 5xx | 백엔드 오류 | 일반 오류 안내 + Trace ID 표시 |

### 4-2. 재시도

| 메서드 | 자동 재시도 |
|---|---|
| GET / HEAD | 502·503·504 에 한해 최대 2회, 지수 백오프 (1s → 2s) |
| PUT / DELETE | 사용자 확인 후 재시도 (멱등이지만 화면 상태 확인 필요) |
| POST / PATCH | **자동 재시도 금지.** `Idempotency-Key` 가 있으면 같은 키로만 재시도 |

> 503 은 집 PC(Local) 가 꺼져 있을 때 **즉시** 돌아온다. 504 는 30초 대기 후 돌아온다.
> 단, local 이 비정상 종료된 직후 약 16초 동안은 생존 신호가 남아 있어 503 대신 30초 뒤 504 가 올 수 있다.
> 원인 구분은 `X-Umd-Error`(OVERLOADED / LOCAL_UNAVAILABLE / REDIS_UNAVAILABLE / SHUTTING_DOWN)로 하되, d94-web 프록시가 응답 헤더를 전달하도록 정비(§5 #3)되기 전에는 UI 에서 보이지 않는다.

### 4-3. 타임아웃

- UMD 는 30초 안에 응답하지 못하면 504 를 준다.
- Next.js 프록시의 백엔드 호출 타임아웃은 **35초** (UMD 보다 길게) — 프록시가 먼저 끊어 상태코드를 잃지 않도록.
- 30초 넘게 걸리는 작업은 백엔드와 **202 + 작업 조회** 방식으로 설계한다 ([backend-guide.md](backend-guide.md) 5장).

### 4-4. 추적

- 모든 응답에 `X-Trace-Id` 가 붙는다 (UMD 가 부여, 미래에는 백엔드가 부여).
- 오류 화면·콘솔 로그에 Trace ID 를 남겨 백엔드·UMD 로그와 대조할 수 있게 한다.

---

## 5. Next.js 프록시 라우트 정비 사항

현재 `src/app/api/proxy/[...path]/route.ts` 는 JSON 전용으로 단순화되어 있어, UMD 를 투명한 REST 통로로 쓰려면 아래를 보완해야 한다.

| # | 현재 | 보완 |
|---|---|---|
| 1 | GET/POST/PUT/DELETE 만 export | `PATCH`, `HEAD` 추가 |
| 2 | 응답을 항상 `res.json()` 으로 다시 만듦 | 상태·헤더·본문을 **그대로 전달** (204, 304, 바이너리, 텍스트 대응) |
| 3 | 응답 헤더 버림 | `Content-Type`, `ETag`, `Location`, `Cache-Control`, `X-Trace-Id` 등 전달 (hop-by-hop 제외) |
| 4 | 요청 헤더는 `Authorization`, `Content-Type` 만 | `Accept`, `Accept-Language`, `If-None-Match`, `If-Modified-Since`, `Idempotency-Key` 추가 전달 |
| 5 | 클라이언트 IP 미전달 | `X-Forwarded-For` 에 브라우저 IP 추가 (UMD 는 Next 서버 IP 뒤에 이어 붙임) |
| 6 | JSON·multipart 외 본문 버림 | 원본 본문 스트림 그대로 전달 |
| 7 | 타임아웃 없음 | `AbortSignal.timeout(35_000)` |
| 8 | 요청마다 `console.log(URL)` | 제거 또는 debug 레벨 |

정비 후 형태 (요지):

```ts
const PASS_REQUEST_HEADERS = ["accept", "accept-language", "content-type",
    "if-none-match", "if-modified-since", "idempotency-key"];
const DROP_RESPONSE_HEADERS = ["connection", "keep-alive", "transfer-encoding", "content-length"];

async function proxyHandler(request: Request, {params}: { params: Promise<{ path: string[] }> }) {
    const {path} = await params;
    const url = new URL(request.url);
    const session = await auth();

    const headers = new Headers();
    PASS_REQUEST_HEADERS.forEach((h) => {
        const v = request.headers.get(h);
        if (v) headers.set(h, v);
    });
    if (session?.user?.accessToken) headers.set("authorization", `Bearer ${session.user.accessToken}`);
    const clientIp = request.headers.get("x-forwarded-for");
    if (clientIp) headers.set("x-forwarded-for", clientIp);

    const hasBody = !["GET", "HEAD"].includes(request.method);
    const res = await fetch(`${process.env.BACKEND_URL}/${path.join("/")}${url.search}`, {
        method: request.method,
        headers,
        body: hasBody ? request.body : undefined,
        // @ts-expect-error Node fetch 스트림 본문 전송 옵션
        duplex: hasBody ? "half" : undefined,
        signal: AbortSignal.timeout(35_000),
    });

    const outHeaders = new Headers(res.headers);
    DROP_RESPONSE_HEADERS.forEach((h) => outHeaders.delete(h));
    return new Response(res.body, {status: res.status, headers: outHeaders});
}

export {proxyHandler as GET, proxyHandler as POST, proxyHandler as PUT,
    proxyHandler as PATCH, proxyHandler as DELETE, proxyHandler as HEAD};
```

> 타임아웃(`AbortError`)은 504, 연결 실패는 502 로 변환해 돌려준다.
> mock 모드(`DEV_MOCK_API`) 분기는 그대로 유지한다.

---

## 6. 로컬 개발 구성

| 구성 | `BACKEND_URL` |
|---|---|
| 백엔드 직접 (가장 단순, 평소 개발) | `http://localhost:8080` |
| UMD 경유 확인 | UMD 를 `UMD_MODE=both` 로 로컬 Redis 에 붙여 실행 → `http://localhost:8081` |
| mock | `DEV_MOCK_API=true` |

UMD 는 투명하므로 **평소에는 백엔드 직접 연결로 개발**하고, 통합 확인 때만 UMD 경유로 바꿔 본다. 두 방식에서 동작이 다르면 UMD 또는 프록시 버그다.

---

## 7. 체크리스트

- [ ] 새 API 함수는 `apiClient` + `/v1/...` 경로만 사용
- [ ] 상태코드로 분기하고 `UMD_*` 코드·`X-Umd-*` 헤더에 의존하지 않음
- [ ] 목록은 페이지네이션, 요청 1MB / 응답 5MB 이하
- [ ] POST 자동 재시도 없음 (필요 시 `Idempotency-Key`)
- [ ] 502/503/504 를 인프라 오류로 안내, Trace ID 표시
- [ ] SSE·WebSocket·대용량 파일은 UMD 경유로 설계하지 않음
- [ ] 새 엔드포인트는 mock 라우트도 추가 (`src/lib/mock/router.ts`)
