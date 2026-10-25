# [context engineering] AI 컨텍스트 문서 체계 도입 — 개발 계획

| 항목 | 내용 |
|---|---|
| 대상 | `d94-app-umd` (+ 교차 저장소 링크: `d94-web`, workspace `david94/`) |
| 참조 모델 | `d94-web` — `AGENTS.md` SSOT, 4계층 문서, `docs/`, ADR 0001~0003, `npm run docs:check` |
| 목표 | 어떤 AI 도구로 열어도 **UMD 의 불변식을 깨지 않고**, 문서가 **코드와 어긋나지 않는** 상태 |
| 작성일 | 2026-10-25 |

---

## 1. 현황 진단

### 1-1. 지금 있는 것 (품질은 좋음)

| 자료 | 줄 수 | 성격 |
|---|---|---|
| `note/architecture/umd-architecture.md` | 244 | **현재 상태** 아키텍처 (흐름·규격·오류·신뢰성·설정). 사실상의 SSOT |
| `note/feature/rest api/ref/{ui,backend}-development-guide.md` | 183 / 231 | 다른 저장소가 지켜야 할 **통합 계약** |
| `note/feature/{init,rest api}/0{1,2}-*.md` | 89~447 | 기능별 계획·결과 **이력** |
| 코드 | main 46 · test 18 파일 | 패키지가 모드와 1:1 (`common` / `server` / `local`), Javadoc 이 한국어로 의도를 설명 |

### 1-2. 문제

| # | 문제 | 영향 |
|---|---|---|
| P1 | `AGENTS.md` / `CLAUDE.md` 없음 | 어떤 도구도 자동으로 컨텍스트를 받지 못함. 매 세션 코드를 처음부터 읽어야 함 |
| P2 | 현재 상태 문서가 `note/`(이력 폴더)에 섞여 있음 | 현재 문서와 폐기된 설계를 AI 가 구분하지 못함 |
| P3 | **폐기된 설계가 이력 문서에 그대로 남아 있음** | 아래 1-3 표. AI 가 eventNm 게이트웨이·lib-core 규격을 "현재"로 오인할 수 있음 |
| P4 | 저장소 밖 경로 참조 (`david94/note/architecture/phase1-architecture.md`, `david94/note/process/deploy/umd.md`) | UMD 는 별도 GitHub 저장소 → 단독 클론·CI·클라우드 세션에서 링크가 끊김 |
| P5 | **셸에서 빌드·테스트를 실행할 수 없음** — `mvn`, `java` 가 PATH 에 없음 (`~/.jdks/jbr-21.0.11` 만 존재, Maven Wrapper 없음) | AI 가 변경을 스스로 검증할 피드백 루프가 없음. 가장 큰 공백 |
| P6 | 통합 테스트가 `@Testcontainers(disabledWithoutDocker = true)` | Docker 가 없으면 **조용히 skip** → "테스트 통과"가 거짓 양성일 수 있음 |
| P7 | 불변식이 아키텍처 문서 곳곳에 흩어져 있음 | "deadline 먼저 확인", "응답 발행 후 ACK", "규격 변경 = 프로토콜 변경" 같은 규칙이 작업 시점에 로드되지 않음 |
| P8 | 문서-코드 동기화 장치 없음 | 오류 코드·설정 키·지표 이름이 늘면 표가 낡음 |

### 1-3. 확인된 문서 드리프트

| 위치 | 문서 내용 | 실제 |
|---|---|---|
| `init/01·02` 전체 | eventNm 태스크 게이트웨이, Stream 규격을 lib-core 에 둠 | rest api 단계에서 **전부 폐기**, 규격은 `tsh.d94.umd.common.spec` |
| `rest api/02` 상태 | "미커밋" | `ff908d2 umd project initialize` 로 커밋됨 |
| `rest api/01` §1 | `NEXT_PUBLIC_API_URL` 만 변경, 경로 예 `/api/...` | UI 변수는 `BACKEND_URL`, 경로는 `/v1/...` (`ui-development-guide.md`, `routes.example.yml`) |

> 원칙: 이력 문서는 고치지 않는다(당시 기록). 대신 **이력임을 표시하고 기본 로드 대상에서 뺀다**(3-3).

---

## 2. 설계 원칙 (d94-web 에서 가져올 것 / 바꿀 것)

| d94-web 원칙 | UMD 적용 |
|---|---|
| ADR 0001: `AGENTS.md` SSOT, `CLAUDE.md` 는 `@AGENTS.md` 한 줄 | **그대로** |
| ADR 0002: L1 프로젝트 / L2 폴더 / L3 화면 / L4 기능 | L1·L2 만. 화면이 없으므로 **L3 = 처리 흐름**을 L2 문서의 `## 흐름` 섹션으로 둠 |
| 컴포넌트 카탈로그가 가장 중요한 컨텍스트 | UMD 에서는 **불변식(Invariants)** 이 가장 중요한 컨텍스트 → L1 에 항상 로드 |
| 규칙(목표)과 예외(현실) 분리 | 그대로 (`docs/conventions.md` 알려진 예외) |
| ADR 0003: 문서 동기화 매핑표 + `docs:check`(Node), 빌드 차단 안 함 | 매핑표는 그대로. 검사는 **JUnit `ContextDocsTest`** 로 → `mvn verify`·CI 에서 자동 실행 (결정 D1) |
| 도구 중립 | 그대로. `.claude/` 전용 설정에 규칙을 쓰지 않음 |

### 로드 예산

| 계층 | 위치 | 로드 시점 | 목표 크기 |
|---|---|---|---|
| L1 | `AGENTS.md` | 항상 | ≤ 150줄 |
| L2 | `src/main/java/tsh/d94/umd/{common,server,local}/AGENTS.md`, `src/test/java/tsh/d94/umd/AGENTS.md` | 해당 패키지 파일을 다룰 때 | 각 ≤ 120줄 |
| docs | `docs/**` | L1·L2 링크를 따라 필요할 때 | 제한 없음 |
| 이력 | `note/**` | **읽지 않음** (사람이 지시할 때만) | — |

---

## 3. 목표 구조

```
d94-app-umd/
├── AGENTS.md                         L1 — 요약·명령어·불변식·절차·동기화 매핑표
├── CLAUDE.md                         @AGENTS.md
├── docs/
│   ├── architecture.md               ← note/architecture/umd-architecture.md 이동 (현재 상태 SSOT)
│   ├── conventions.md                Java 코드 규칙 + 알려진 예외
│   ├── dev-setup.md                  JDK/Maven/Docker, env, 실행 방법, Windows 주의
│   ├── deployment.md                 ← david94/note/process/deploy/umd.md 이동 (결정 D2)
│   ├── known-issues.md               결과 문서 "남은 작업" 수집
│   ├── integration/
│   │   ├── ui-guide.md               ← note/feature/rest api/ref/ui-development-guide.md
│   │   └── backend-guide.md          ← note/feature/rest api/ref/backend-development-guide.md
│   ├── templates/
│   │   ├── feature-plan.md           01-development-plan 양식
│   │   └── feature-result.md         02-development-result 양식
│   └── decisions/                    ADR
├── note/
│   ├── README.md                     "이력 문서. 현재 상태는 docs/. 충돌 시 코드 > docs > note"
│   └── feature/{기능}/01·02-*.md     기능별 계획·결과 (지금처럼 유지)
└── src/
    ├── main/java/tsh/d94/umd/
    │   ├── common/AGENTS.md + CLAUDE.md   L2 — 모드·설정·헤더 정책·Stream 규격(프로토콜)
    │   ├── server/AGENTS.md + CLAUDE.md   L2 — /** 프록시, 사전 차단, 응답 복원
    │   └── local/AGENTS.md + CLAUDE.md    L2 — 소비·라우팅·호출·회수·생존 신호
    └── test/java/tsh/d94/umd/AGENTS.md + CLAUDE.md   L2 — 테스트 계층·Testcontainers·FakeLocalConsumer
```

### 3-1. L1 `AGENTS.md` 목차

| 절 | 내용 |
|---|---|
| §0 문서 지도 | 위 트리 (L2 등록 원본) |
| §1 요약 | HTTP ↔ Redis Stream 투명 프록시, 한 jar 두 모드(`server`/`local`/`both`), 최종 목표 = UMD·Redis 제거 시 직결 |
| §2 스택 | Java 21(preview), Boot 3.4.1 (`d94-lib-pom` parent), Spring Data Redis, Lombok, Log4j2, Micrometer, Testcontainers 1.21.4 |
| §3 명령어 | 빌드·테스트·단일 테스트·both 모드 실행·compose 기동. **Docker 없으면 통합 테스트 skip** 경고 |
| §4 디렉터리 맵 | 패키지 → L2 문서 |
| §5 **불변식** (가장 중요) | 아래 3-2 |
| §6 금지 | 업무 로직·경로 변환·UMD 전용 헤더 의존·`@ConditionalOnUmdMode` 없는 모드 Bean·메트릭 태그에 경로 등 |
| §7 작업 절차 | 기능 = `note/feature/{기능}/01-plan` → 개발 → `02-result` → **`docs/` 반영** → 결정이면 ADR |
| §8 완료 체크리스트 | `mvn -B clean verify` 통과 + **surefire skipped 0 확인** + 동기화 매핑표 수행 + 보고에 갱신 문서 목록 |
| §9 docs 색인 | "언제 읽나" 표 |
| §10 문서 동기화 | 매핑표 (3-4) |

### 3-2. 불변식 (L1 §5 초안)

| # | 불변식 | 근거 코드 |
|---|---|---|
| I1 | **UMD 는 업무를 모른다.** 메서드·경로·쿼리·헤더·본문·상태를 그대로 나른다. 새 API 때문에 UMD 코드를 고치지 않는다 (새 로컬 서비스는 `routes` 한 줄) | `StreamProxyService`, `LocalRouteResolver` |
| I2 | 경로는 바뀌지 않는다 — context-path 없음, 라우팅은 prefix 선택만 | `application.yml`, `LocalRouteResolver` |
| I3 | 인증·CORS 는 백엔드 소유. UMD 의 JWT 확인은 Redis 앞 차단용. OPTIONS 는 무토큰 통과 | `server.security` |
| I4 | 모드별 Bean 은 반드시 `@ConditionalOnUmdMode`. `local` 은 JWT 키 없이 기동해야 한다 | `LocalModeIntegrationTest` |
| I5 | **Stream 규격 변경 = 프로토콜 변경.** server(AP-01)와 local(Home PC)은 따로 배포되므로 필드 추가는 양쪽 하위 호환, 삭제·의미 변경은 ADR + 배포 순서 명시 | `common.spec` |
| I6 | local 은 **deadline 을 먼저 확인**하고, 만료면 백엔드를 부르지 않고 ACK (POST 중복 실행 방지) | `LocalRequestProcessor`, `PendingReclaimer` |
| I7 | 응답 발행 성공 후에만 XACK. 요청·응답 엔트리는 처리 직후 XDEL (민감 데이터) | `LocalRequestProcessor`, `ReplyStreamListener` |
| I8 | UMD 자체 오류는 HTTP 상태 + `UmdErrorCode`(`X-Umd-Error`). 백엔드 응답은 손대지 않는다 | `common.error` |
| I9 | `poll-timeout` < Redis 명령 타임아웃 | `UmdSettingsValidator` |
| I10 | 지표 태그에 경로 금지, `status` 는 클래스(`2xx`) | `ServerStreamMetrics`, `LocalMetrics` |

### 3-3. L2 문서 양식 (패키지)

```md
# L2 · {package} (`tsh.d94.umd.{package}`)
> 계층: [L1](…) › **L2 {package}**   활성 모드: server | local | both
## 역할
## 클래스              | 클래스 | 책임 | 관련 설정 · 지표 · 오류 코드 |
## 흐름                요청 한 건이 이 패키지를 지나는 순서 (L3 대체)
## 이 패키지의 불변식   L1 §5 중 해당 항목 + 패키지 고유 규칙
## 주의 · 예외
```

- `common/AGENTS.md` 는 `spec` 하위 패키지를 함께 다룬다 (Stream 필드표·`bodyEncoding` 판정·헤더 정규화). 규격 변경 체크리스트(I5)를 여기 둔다.
- `test/AGENTS.md`: 단위 / Redis 통합(`*IntegrationTest`) / 종단(`both` 모드) 구분, `FakeLocalConsumer` 사용법, 랜덤 포트 판정은 `ManagementPortType`, Windows `MSYS_NO_PATHCONV=1`.

### 3-4. 문서 동기화 매핑표 (L1 §10 초안)

| 코드 변경 | 갱신할 문서 |
|---|---|
| 클래스 추가·삭제·이름 변경 | 해당 패키지 L2 "클래스" 표 |
| 처리 순서·재시도·ACK/XDEL 시점 변경 | L2 "흐름" + `docs/architecture.md` §3·§8 |
| Stream 키·필드 변경 (`common.spec`) | `docs/architecture.md` §4 + `common/AGENTS.md` + **ADR** (I5) |
| `UmdErrorCode` 추가·변경 | `docs/architecture.md` §7 + `docs/integration/ui-guide.md` 상태코드 표 |
| `umd.*` 설정 추가·기본값 변경 | `docs/architecture.md` §11 + `application.yml` 주석 |
| 지표·health 추가·변경 | `docs/architecture.md` §10 |
| 환경 변수 추가·변경 | `.env.*.example` + `docs/dev-setup.md` / `docs/deployment.md` |
| 헤더 정책 변경 | `docs/architecture.md` §6 + `docs/integration/{ui,backend}-guide.md` |
| compose·Dockerfile·CI 변경 | `docs/deployment.md` |
| 의존성·명령어 변경 | L1 §2·§3 |
| 규칙 예외 생성·해소 | `docs/conventions.md` 알려진 예외 |
| 알려진 문제 발견·해결 | `docs/known-issues.md` |
| 설계 결정 | `docs/decisions/` ADR + L1 해당 절 |

### 3-5. ADR (기존 결정을 이력 문서에서 추출)

| ADR | 내용 | 출처 |
|---|---|---|
| 0001 | AI 컨텍스트 문서 체계 (AGENTS.md SSOT, L1/L2, docs vs note 분리, 동기화 검사) | 이 계획 |
| 0002 | eventNm 게이트웨이 폐기 → 투명 REST 프록시 | `rest api/01` §1 |
| 0003 | Stream 규격을 lib-core 가 아닌 UMD 내부에 둠 (재검토 조건 포함) | `rest api/01` §3-0 |
| 0004 | server 를 MVC 컨트롤러가 아닌 서블릿 필터 + AsyncContext 로 | `rest api/02` §4 |
| 0005 | `X-Forwarded-For` 덮어쓰기 대신 이어 붙이기 | `rest api/02` §4 |

### 3-6. 자동 검사 `ContextDocsTest` (JUnit, 의존성 추가 없음)

| 검사 (실패 = 빌드 실패) | 방법 |
|---|---|
| 문서 링크 | 모든 `*.md` 상대 링크 대상 존재 |
| `AGENTS.md` / `CLAUDE.md` 짝 | 같은 폴더에 `@AGENTS.md` 포함 `CLAUDE.md` |
| L1 §0 지도 | 모든 `AGENTS.md` 가 지도에 등록 |
| 클래스 표 | `src/main/java/**/X.java` 마다 가장 가까운 L2 문서에 `X` 등장 |
| 오류 코드 | `UmdErrorCode` 상수가 `docs/architecture.md` 에 모두 등장 |
| 설정 키 | `UmdProperties` 필드(kebab-case)가 `docs/architecture.md` §11 에 등장 |
| 지표 이름 | 코드의 `umd_*` 문자열이 `docs/architecture.md` §10 에 등장 |
| 환경 변수 | `.env.*.example` 의 키가 `docs/dev-setup.md` 또는 `docs/deployment.md` 에 등장 |
| 저장소 밖 경로 | `../../` 로 저장소 밖을 가리키는 링크 금지 → GitHub URL 로 (P4 재발 방지) |

- d94-web 의 git diff 기반 **경고**(코드 변경 대비 문서 미변경)는 JUnit 에 넣지 않는다. CI 에서 의미가 없고, L1 §8 체크리스트로 대신한다.
- 판별할 수 없는 것(설명이 맞는지)은 검사하지 않는다 — d94-web ADR 0003 과 같은 한계.

---

## 4. 단계별 작업

| 단계 | 작업 | 산출물 | 해결 |
|---|---|---|---|
| 0 | **검증 루프 확보**: Maven Wrapper(`mvnw`) 추가, `JAVA_HOME`(JDK 21) 설정 방법 정리, 기준선 `./mvnw -B clean verify` 실행 → 테스트 126건·skipped 0 확인 | `mvnw`, `.mvn/wrapper/` | P5, P6 |
| 1 | **골격**: L1 `AGENTS.md`+`CLAUDE.md`, `docs/` 생성 및 이동(architecture, integration, deployment), `dev-setup`·`conventions`·`known-issues` 작성, `note/README.md` 이력 표기, 이동에 따른 링크 수정 | 3장 트리의 루트·docs | P1, P2, P3, P4, P7 |
| 2 | **L2**: `common`·`server`·`local`·`test` 문서 4쌍 (클래스 표는 코드에서 복사), ADR 0001~0005, feature 템플릿 2종 | L2 8파일, ADR 5개 | P7 |
| 3 | **자동 검사**: `ContextDocsTest` 작성 → 위반 0 이 될 때까지 문서 보정. CI(`deploy.yml`)는 `mvn verify` 이므로 추가 설정 없음 | `src/test/.../ContextDocsTest.java` | P8 |
| 4 | **교차 저장소** (별도 커밋·저장소) | 아래 표 | — |
| 5 | **효과 검증**: 새 세션에서 문서만으로 아래 질문에 답하게 하고 정답 비교 | 결과 문서 `02-development-result.md` | — |

### 4단계 — 교차 저장소

| 저장소 | 변경 |
|---|---|
| d94-web | `src/lib/AGENTS.md` §4 "백엔드 API 명세 위치 미문서화" → UMD `docs/integration/ui-guide.md` (GitHub URL) 링크 |
| d94-web | `docs/architecture.md` 요청 흐름에 UMD 홉, 502/503/504·`X-Umd-Error` 처리 추가 |
| d94-web | `docs/known-issues.md` 에 프록시 라우트 정비(PATCH/HEAD, 헤더·응답 그대로 전달, 35초 타임아웃) 등록 |
| david94 (workspace) | 루트 `AGENTS.md`+`CLAUDE.md`: 저장소 지도(web → UMD server → Redis → UMD local → query/backend), 각 저장소 L1 링크. `note/process/deploy/umd.md` 는 UMD 저장소 링크로 교체 |

### 5단계 — 효과 검증 질문 (새 세션, 코드 탐색 없이 문서 기준)

| 질문 | 기대 답 |
|---|---|
| 새 로컬 서비스 `/v1/mpms` 를 붙이려면? | `config/routes.yml` 에 route 한 줄. UMD 코드 변경 없음 (I1) |
| Stream 요청에 필드를 하나 추가하려면? | 양쪽 하위 호환, local 먼저/server 먼저 배포 순서, ADR (I5) |
| server 전용 Bean 을 추가하려면? | `@ConditionalOnUmdMode({SERVER, BOTH})`, local 기동 테스트 확인 (I4) |
| 회수된 POST 요청이 이미 타임아웃이면? | 백엔드 호출 없이 ACK (I6) |
| "테스트 다 통과했다"를 어떻게 확인? | `mvnw verify` + surefire skipped 0 (Docker 필요) |
| UI 가 503 을 받았다. 원인 후보는? | `X-Umd-Error` 로 구분: OVERLOADED / LOCAL_UNAVAILABLE / REDIS_UNAVAILABLE / SHUTTING_DOWN |

---

## 5. 결정 필요 사항

| # | 질문 | 권장 | 이유 |
|---|---|---|---|
| D1 | `ContextDocsTest` 위반 시 빌드를 실패시킬까 | **실패시킴** | d94-web(연습장)과 달리 UMD 는 배포되는 인프라이고 문서 표면이 작아 비용이 낮음. 문서가 운영 계약(오류 코드·설정)을 겸함 |
| D2 | 배포 문서 `david94/note/process/deploy/umd.md` 를 UMD 저장소로 옮길까 | **옮김** (workspace 쪽은 링크만) | 별도 저장소에서 compose·env 와 같은 커밋으로 동기화 가능 |
| D3 | `note/` 이력 문서 처리 | **유지 + `note/README.md` 표기**, 본문 수정 안 함 | 기록 가치 보존. 기본 로드 대상이 아니므로 드리프트 영향 차단 |
| D4 | 커밋 메시지 규칙 | d94-web 과 같은 `type: 설명` | 현재 이력 2건은 규칙 없음. workspace 전체 일관성 |

---

## 6. 완료 기준

- `./mvnw -B clean verify` 가 셸에서 실행되고 테스트 전체 통과, skipped 0 (`ContextDocsTest` 포함)
- `AGENTS.md`/`CLAUDE.md` 5쌍, `docs/` 구성 완료, 저장소 밖 링크 0
- 이력 문서가 기본 로드 대상에서 빠지고 `note/README.md` 로 표시됨
- 5단계 검증 질문 6개를 새 세션이 문서만으로 맞힘
- 결과 문서 `02-development-result.md` 작성
