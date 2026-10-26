# 배포 절차 — UMD (d94-app-umd, AP-01 server / Home PC local)

> HTTP ↔ Redis Stream 프록시. **같은 이미지**를 AP-01 에서는 `server`, Home PC 에서는 `local` 모드로 띄운다.
> **CI 는 테스트·이미지 빌드·푸시까지, 서버 반영은 수동(docker compose)** — Query 와 같은 방식.
> 구조 설명: [architecture.md](architecture.md) · 2026-10-25 workspace `david94/note/process/deploy/umd.md` 에서 이동 (원래 위치에는 링크만 남김)

## 한눈에 보기

```
[1] 이미지 빌드 (GitHub Actions)
    PR → deploy            ─▶ mvn verify (단위 + Testcontainers Redis 통합 테스트)
    push deploy / tag v*   ─▶ mvn verify ─▶ docker build ─▶ ghcr.io/david-yj-52/d94-app-umd

[2] AP-02 Redis 준비 (최초 1회)
    TLS / ACL 사용자(umd-server, umd-local) / 방화벽(AP-01, Home PC IP)

[3] AP-01 (server)                         [4] Home PC (local)
    .env.server 작성                          .env.delap + config/routes.yml 작성
    compose -f docker-compose.server.yml up    compose -f docker-compose.delap.yml up
                     └──────────── 둘 다 healthy → UI BACKEND_URL = AP-01:8081 ───────┘
```

## 0. 구성

```
[ Next.js 서버 ] ──HTTPS──▶ AP-01 :8081  UMD server (JWT 사전 확인)        :8091 actuator (내부)
                                   │ XADD d94:umd:req / XREAD d94:umd:rep:umd-ap01
                                   ▼
                            AP-02 :6379  Redis (AOF, TLS/ACL)
                                   ▲ XREADGROUP home-backend / XADD / XACK / SET alive  (아웃바운드)
                                   │
                            Home PC      UMD local (앱 포트 없음)              :8092 actuator (내부)
                                   │ HTTP (경로 그대로)
                                   ▼
                            Local REST Backend(s)  예) host.docker.internal:8080
```

## 1. 이미지 빌드 — `.github/workflows/deploy.yml`

| 이벤트 | 동작 | 태그 |
|---|---|---|
| `deploy` 대상 PR | `mvn -B clean verify` | - |
| `deploy` push | 테스트 → 빌드 → 푸시 | `sha-xxxxxxx` (⚠ `latest` 는 main 일 때만 붙도록 되어 있어 현재 붙지 않음 → [known-issues](known-issues.md)) |
| `v1.2.3` 태그 | 테스트 → 빌드 → 푸시 | `1.2.3`, `sha-xxxxxxx` |

- 필요 Secret: `PACKAGES_TOKEN` (classic PAT, `read:packages`) — parent pom(`github-d94`), `d94-lib-core`(`github-lib-core`) 다운로드용
- UMD 는 lib-core 의 `JwtService`, `ApiResponse` 만 사용 (Stream 규격은 UMD 내부) → lib-core 변경 없이 단독 배포 가능

## 2. AP-02 Redis 준비 (최초 1회)

Home PC 가 인터넷 너머에서 접속하므로 Redis 를 그대로 열면 안 된다.

| 항목 | 권장 |
|---|---|
| 전송 | TLS (`tls-port 6379`, `port 0`) → UMD 에 `REDIS_SSL_ENABLED=true`. 또는 WireGuard/SSH 터널 내부로만 접속 |
| 인증 | ACL 사용자 분리 (아래), `default` 사용자 비활성 |
| 방화벽 | AP-02:6379 인바운드를 **AP-01 과 Home PC 공인 IP** 로만 허용 |
| 영속성 | AOF 유지 (요청·응답 엔트리는 처리 후 바로 삭제되므로 용량 부담 작음) |

ACL 예시 (`users.acl`):

```
user default off
user umd-server on >서버비밀번호 ~d94:umd:* +xadd +xread +xdel +del +expire +get +exists +xgroup|create +ping +info
user umd-local  on >로컬비밀번호 ~d94:umd:* +xreadgroup +xack +xdel +xadd +xpending +xclaim +xgroup|create +set +get +del +ping +info
```

UMD 쪽 설정: `REDIS_PASSWORD` + compose 의 `SPRING_DATA_REDIS_USERNAME` 주석 해제 (`umd-server` / `umd-local`).

> Redis Insight 기본 포트는 **5540** 이다 (phase1 문서의 15672 는 RabbitMQ 관리 포트).

## 3. AP-01 — server 모드

**최초 1회**

```bash
docker network create d94-shared
cp .env.server.example .env.server      # 값 채우기
docker login ghcr.io                    # 이미지가 private 이면
```

**배포 / 업데이트**

```bash
docker compose -f docker-compose.server.yml --env-file .env.server pull
docker compose -f docker-compose.server.yml --env-file .env.server up -d
docker ps                               # d94-umd-server 가 healthy 인지 (start-period 40s)
curl -s localhost:8091/actuator/health
curl -s localhost:8091/actuator/info    # "mode":"server"
```

| 변수 | 기본값 | 비고 |
|---|---|---|
| `IMAGE_TAG` | `latest` | 롤백 시 이전 태그 |
| `APP_PORT` | `8081` | 외부 공개 |
| `MANAGEMENT_BIND`, `MANAGEMENT_HOST_PORT` | `127.0.0.1`, `8091` | actuator. **외부 공개 금지** |
| `UMD_INSTANCE_ID` | `umd-ap01` | 응답 Stream 이름. server 마다 고유 |
| `UMD_REQUEST_TIMEOUT` | `30s` | local 응답 대기 |
| `UMD_CORS_ORIGINS` | (없음) | UMD 자체 오류 응답에 붙일 Origin (쉼표 구분) |
| `UMD_SERVER_PUBLICPATHS` | `/tsh/api/v1/auth/**` | 토큰 없이 통과시킬 경로(`umd.server.public-paths`, 쉼표 구분). **로그인·회원가입 경로 필수** — 빠지면 UI 로그인이 `401 UMD_UNAUTHORIZED`. 경로는 UI `BACKEND_URL` 기준(context-path 포함). compose `environment:` 에 적힌 변수만 컨테이너에 전달되므로 `.env` 에만 넣으면 안 된다 |
| `REDIS_HOST` (필수), `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` | | AP-02 |
| `JWT_SECRET_KEY` (필수) | | **d94-query 와 같은 값** |

## 4. Home PC — local 모드

**최초 1회**

```bash
cp .env.delap.example .env.delap                 # REDIS_* 채우기 (SHARED_NETWORK_NAME 기본 befw-cloud-network)
cp config/routes.example.yml config/routes.yml   # 게이트웨이(백엔드) 주소로 수정 — 반드시 기동 전에
```

구성 (delap): UMD local 은 **게이트웨이 하나로만** 보낸다. Eureka·Config 서버는 UMD 와 관계없다 (게이트웨이가 Eureka 로 서비스에 분배).

```
Redis ─▶ UMD local ─(routes.yml)─▶ gateway-cloud-gateway-1 :10001 ─(Eureka)─▶ d94-query 등
```

`config/routes.yml` 예:

```yaml
umd:
  local:
    routes:
      - id: gateway
        path-prefix: /
        uri: http://gateway-cloud-gateway-1:10001
```

- 경로는 바꾸지 않는다. UI `BACKEND_URL` 에 `/tsh/api` 가 있으므로 게이트웨이에는 `/tsh/api/v1/...` 가 그대로 도착한다 → 게이트웨이에 `Path=/tsh/**` route 가 있어야 한다. context-path 는 `BACKEND_URL` 과 uri 중 한 곳에만 둔다.
- uri 주소: 같은 네트워크(`SHARED_NETWORK_NAME`)의 컨테이너는 **컨테이너 이름 + 컨테이너 내부 포트**, 호스트에서 직접 띄운 프로세스나 포트를 publish 한 컨테이너는 `host.docker.internal:{호스트 포트}`.
- compose 는 `SHARED_NETWORK_NAME`(기본 `befw-cloud-network`, 게이트웨이·Eureka·Config 네트워크) 네트워크를 **external** 로 붙는다. 게이트웨이 컨테이너도 같은 네트워크에 있어야 하고, 네트워크가 없으면 기동이 실패한다.
- `config/routes.yml` 없이 기동하면 docker 가 그 자리에 빈 디렉터리를 만들어 마운트한다 → routes 가 비어 모든 요청이 404 `UMD_NO_ROUTE`. 디렉터리가 생겼으면 지우고 파일로 다시 만든다.
- routes 는 **기동 시에만** 읽는다 (compose 가 `./config/routes.yml` 을 읽기 전용 마운트 → `SPRING_CONFIG_ADDITIONAL_LOCATION`). jar·이미지에 들어가지 않으므로 바꿀 때 빌드는 필요 없고, `up -d --force-recreate` 또는 `restart` 로 재기동한다. 새 서비스 패턴이 생기면 `config/routes.example.yml` 에도 예시를 남긴다.

연결 확인 (토큰이 없으므로 401/403 이면 게이트웨이·백엔드까지 닿은 것, 404 면 게이트웨이 경로 규칙 문제):

```bash
docker exec d94-umd-local wget -S -qO- http://gateway-cloud-gateway-1:10001/tsh/api/v1/interface-tests 2>&1 | head -3
```

**배포 / 업데이트**

```bash
docker compose -f docker-compose.delap.yml --env-file .env.delap pull
docker compose -f docker-compose.delap.yml --env-file .env.delap up -d
curl -s localhost:8092/actuator/health
curl -s localhost:8092/actuator/info    # "mode":"local"
```

| 변수 | 기본값 | 비고 |
|---|---|---|
| `IMAGE_TAG` | `latest` | server 와 같은 버전 권장 |
| `MANAGEMENT_HOST_PORT` | `8092` | actuator 호스트 포트, `127.0.0.1` 전용 (컨테이너 안은 server 와 같은 8091) |
| `UMD_INSTANCE_ID` | `umd-home01` | Consumer 이름. 집 PC 마다 고유 |
| `REDIS_HOST` (필수), `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` | | AP-02 (아웃바운드) |
| `SHARED_NETWORK_NAME` | `befw-cloud-network` | 게이트웨이와 공유하는 기존 docker 네트워크 (external) |

- JWT 키는 필요 없다 (local 은 토큰을 검증하지 않고 백엔드로 그대로 전달).
- 인바운드 포트를 열지 않는다. 라우터 포트포워딩 불필요.

## 5. 배포 후 확인

```bash
# AP-01 에서: 생존 신호를 본 server 가 local 을 인식했는지
curl -s localhost:8091/actuator/prometheus | grep umd_proxy_rejected_total   # LOCAL_UNAVAILABLE 증가가 멈췄는지

# UI 경로로 한 건 호출 (토큰 필요)
curl -i -H "Authorization: Bearer <token>" https://<AP-01>:8081/v1/<아무 API>
#  → 백엔드 응답 그대로 + X-Trace-Id. 503 UMD_LOCAL_UNAVAILABLE 이면 local 미기동/Redis 연결 확인
```

| 확인 | 방법 |
|---|---|
| local 이 붙었나 | Redis `GET d94:umd:local:alive` → local instanceId |
| 요청이 쌓이나 | `XLEN d94:umd:req` 가 0 근처 (처리 후 바로 삭제) / `XPENDING d94:umd:req home-backend` |
| 지연·결과 | server `umd_proxy_request_seconds{outcome}`, local `umd_local_dispatch_seconds{route,outcome}` |
| 추적 | 응답의 `X-Trace-Id` 로 server·local·백엔드 로그 검색 |

## 6. 롤백 / 순서

- **롤백**: 각 `.env` 의 `IMAGE_TAG` 를 이전 태그로 바꾸고 `pull` → `up -d`.
- **순서 무관**: server·local 모두 Consumer Group 을 만들므로 어느 쪽을 먼저 띄워도 된다. local 이 없는 동안 server 는 즉시 503.
- **버전 호환**: 선택 필드 추가처럼 하위 호환인 Stream 변경은 순서 무관. 필수 필드·의미 변경(호환 불가)은 해당 ADR 의 순서대로 또는 server·local 을 함께 올린다 (L1 I5).

## 주의 사항

- **actuator 포트(8091/8092)는 인증이 없다** → `127.0.0.1` 바인딩 유지. Prometheus 가 다른 서버면 `MANAGEMENT_BIND` 를 내부망 IP 로, 방화벽으로 수집 서버만 허용.
- **`umd.*.poll-timeout` 은 `spring.data.redis.timeout`(3s) 보다 짧아야 한다** — 어기면 기동 실패 (의도된 검사).
- **`UMD_INSTANCE_ID` 를 서버마다 다르게** — 같은 값을 두 server 가 쓰면 응답이 섞인다.
- **종료 대기**: server 45s / local 40s (`stop_grace_period`). 줄이면 처리 중 요청이 끊긴다.
- **Windows Git Bash 에서 docker 명령에 `/` 로 시작하는 값을 넘길 때** (예: `-e UMD_LOCAL_ROUTES_0_PATHPREFIX=/`) 경로로 바뀌는 문제가 있다 → `MSYS_NO_PATHCONV=1` 또는 routes 파일 사용.
- CD 자동화 없음 → 이미지 푸시 후 각 서버에서 직접 `pull` / `up`.
