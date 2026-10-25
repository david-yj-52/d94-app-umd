# AGENTS.md — d94-app-umd (UMD, UI Mediator)

> 모든 AI 코딩 도구(Claude Code, Cursor, Codex, Copilot 등)가 공통으로 읽는 **L1 프로젝트 가이드**입니다.
> 도구별 파일(`CLAUDE.md`)은 같은 폴더의 `AGENTS.md`를 import 하기만 합니다. 규칙은 **AGENTS.md 계열과 `docs/`에만** 씁니다.

## 0. 문서 지도

| 계층 | 범위 | 위치 | 로드 |
|---|---|---|---|
| **L1 프로젝트** | 요약, 명령어, 불변식, 절차 | 이 파일 | 항상 |
| **L2 패키지** | 클래스 표, 처리 흐름, 패키지 규칙 | 아래 트리 | 해당 패키지 작업 시 |
| docs | 현재 상태의 상세 | [docs/](docs/) | 필요할 때 링크로 |
| 이력 | 기능별 계획·결과 기록 | [note/](note/README.md) | **기본으로 읽지 않음** |

```
AGENTS.md (L1)
├── src/main/java/tsh/d94/umd/
│   ├── common/AGENTS.md .......... 모드·설정·헤더 정책·오류 코드·Stream 규격(프로토콜)
│   ├── server/AGENTS.md .......... [server] /** 프록시, 사전 차단, 응답 복원, JWT
│   └── local/AGENTS.md ........... [local] 소비·라우팅·로컬 호출·회수·생존 신호
└── src/test/java/tsh/d94/umd/AGENTS.md ... 테스트 계층, Testcontainers, FakeLocalConsumer
```

**새 패키지를 만들면 이 지도에 등록하고, 같은 폴더에 `AGENTS.md`와 `CLAUDE.md`(`@AGENTS.md` 한 줄)를 함께 만드세요.**

## 1. 프로젝트 요약

- **HTTP ↔ Redis Stream 투명 프록시.** 방화벽 인바운드를 열지 않고 클라우드 UI(d94-web)가 Home PC 의 REST 백엔드를 호출하게 합니다.
- **한 jar, 두 모드** (`umd.mode` / `UMD_MODE`): `server`(AP-01, UI 요청 수신) · `local`(Home PC, 로컬 백엔드 호출) · `both`(개발·테스트 전용, 운영 금지).
- **최종 목표:** 백엔드를 서버에 올릴 때 UMD 와 Redis 만 빼고 UI 의 `BACKEND_URL` 만 바꾸면 그대로 동작해야 합니다.
- 상세: [docs/architecture.md](docs/architecture.md)

## 2. 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어·런타임 | Java 21 (`--enable-preview`), 가상 스레드 |
| 프레임워크 | Spring Boot 3.4.1 (parent `d94-lib-pom`), Spring Security, Spring Data Redis |
| 공유 라이브러리 | `d94-lib-core` — `JwtService`, `ApiResponse` 만 사용 (DB 미사용) |
| 기타 | Lombok, Log4j2, Micrometer(Prometheus), Actuator(컨테이너 안 별도 포트 8091) |
| 테스트 | JUnit 5, Testcontainers 1.21.4 (`redis:7-alpine`) |
| 빌드 | Maven Wrapper (`./mvnw`, Maven 3.9.9) |

## 3. 명령어

| 목적 | 명령 | 비고 |
|---|---|---|
| 전체 검증 | `./mvnw -B clean verify` | **작업 후 반드시 실행.** JDK 21·Docker 필요 → [docs/dev-setup.md](docs/dev-setup.md) |
| 단일 테스트 | `./mvnw -B test -Dtest=LocalRouteResolverTest` | |
| 패키징만 | `./mvnw -B -DskipTests package` | |
| 문서 검사 | `./mvnw -B test -Dtest=ContextDocsTest` | 전체 검증에도 포함됨 → §10 |

⚠️ **Docker 가 없으면 통합 테스트가 실패하지 않고 skip 됩니다** (`disabledWithoutDocker = true`).
"통과"를 보고하기 전에 출력의 `Tests run: N, ... Skipped: 0` 을 확인하세요.

## 4. 디렉터리 맵

```
src/main/java/tsh/d94/umd/
├── UmdApplication.java       스캔 범위 tsh.d94.umd, DB 자동설정 제외
├── common/  (+ error/, spec/) 양 모드 공통             → common/AGENTS.md
├── server/  (+ proxy/, security/)                      → server/AGENTS.md
└── local/                                              → local/AGENTS.md
src/main/resources/application.yml   umd.* 설정 기본값
config/routes.example.yml            local 라우팅 예 (실제 routes.yml 은 git 제외)
docker-compose.{server,local}.yml, .env.{server,local}.example, Dockerfile, .github/workflows/deploy.yml
```

## 5. 불변식 (가장 중요 — 깨면 안 됨)

| # | 불변식 |
|---|---|
| I1 | **UMD 는 업무를 모른다.** 메서드·경로·쿼리·헤더·본문·상태를 그대로 나른다. 새 API 때문에 UMD 코드를 고치지 않는다. 새 로컬 서비스는 `routes` 한 줄 |
| I2 | **경로는 바뀌지 않는다.** context-path 없음, local 라우팅은 prefix 로 대상만 고른다 |
| I3 | **인증·CORS 는 백엔드 소유.** server 의 JWT 확인은 Redis 앞 차단용. `Authorization` 원본 전달, OPTIONS 는 무토큰 통과 |
| I4 | **모드별 Bean 은 반드시 `@ConditionalOnUmdMode`.** `local` 은 JWT 키 없이 기동해야 한다 |
| I5 | **Stream 규격 변경 = 프로토콜 변경.** server(AP-01)와 local(Home PC)은 따로 배포된다. 필드 추가는 양쪽 하위 호환으로, 삭제·의미 변경은 ADR + 배포 순서 명시 → [common/AGENTS.md](src/main/java/tsh/d94/umd/common/AGENTS.md) |
| I6 | **local 은 deadline 을 먼저 확인한다.** 만료면 백엔드를 부르지 않고 ACK (타임아웃 후 POST 중복 실행 방지) |
| I7 | **응답 발행에 성공한 뒤에만 XACK.** 요청·응답 엔트리는 처리 직후 XDEL (토큰·본문을 Redis 에 남기지 않음) |
| I8 | **UMD 자체 오류는 HTTP 상태 + `UmdErrorCode`(`X-Umd-Error`).** 백엔드 응답은 상태·헤더·본문을 손대지 않는다 |
| I9 | `umd.*.poll-timeout` < `spring.data.redis.timeout` (`UmdSettingsValidator` 가 기동 시 검사) |
| I10 | **지표 태그에 경로 금지**, `status` 는 클래스(`2xx`). 지표 이름은 `umd_` 접두사 |

## 6. 금지

- UMD 에 업무 로직, 경로 변환, 특정 API 분기 추가 (I1·I2)
- 백엔드·UI 가 `X-Umd-*` 헤더에 의존하게 만드는 설계 (진단용일 뿐)
- server 를 MVC 컨트롤러로 바꾸기, `FormContentFilter` 재활성화 → [ADR 0004](docs/decisions/0004-servlet-filter-proxy.md)
- 운영 설정에 `UMD_MODE=both`
- lib-core 전체 스캔 (`@ComponentScan` 확장) — JPA/DB Bean 이 따라 올라옴
- 비밀값(`JWT_SECRET_KEY`, `REDIS_PASSWORD`)을 `application.yml`·예시 파일에 기록

기존 코드의 예외는 [docs/conventions.md](docs/conventions.md) §6 에 있습니다. **고치지 않은 예외를 따라 하지 마세요.**

## 7. 작업 절차 (기능 단위)

1. **계획:** `note/feature/{기능}/01-development-plan.md` ([양식](docs/templates/feature-plan.md))
2. **개발:** 각 단계에서 테스트를 함께 작성 ([테스트 L2](src/test/java/tsh/d94/umd/AGENTS.md))
3. **결과:** `note/feature/{기능}/02-development-result.md` ([양식](docs/templates/feature-result.md))
4. **현재 상태 반영:** §10 매핑표에 따라 `docs/`·L2 갱신. `note/` 에만 쓰고 끝내지 않습니다
5. **결정:** 설계 결정이면 [docs/decisions/](docs/decisions/) 에 ADR

## 8. 작업 완료 전 체크리스트

- [ ] `./mvnw -B clean verify` 통과, **Skipped: 0** 확인
- [ ] §10 문서 동기화 수행 (`ContextDocsTest` 는 verify 에 포함)
- [ ] 작업 보고에 "갱신한 문서 목록" 포함 (갱신 불필요라면 사유)

## 9. 커밋 메시지

`type: 설명` (한국어 가능). type 은 `feat`, `fix`, `refactor`, `docs`, `test`, `chore` 중 하나. 예: `feat: local 라우트 우선순위 로그 추가`

## 10. 문서 동기화 (필수)

컨텍스트 문서는 **항상 현재 코드와 일치**해야 합니다. 코드 변경과 문서 변경은 같은 작업·같은 커밋에서 합니다.
문서에는 코드에서 확인한 사실만 쓰고, 클래스명·설정 키·오류 코드·지표 이름은 **코드에서 그대로 복사**합니다.

| 코드 변경 | 갱신할 문서 |
|---|---|
| 클래스 추가·삭제·이름 변경 | 해당 패키지 L2 "클래스" 표 |
| 처리 순서·재시도·ACK/XDEL 시점 변경 | L2 "흐름" + [architecture](docs/architecture.md) §3·§8 |
| Stream 키·필드 변경 (`common.spec`) | architecture §4 + common L2. 필수 필드·의미 변경(호환 불가)이면 **ADR** + 배포 순서 (I5) |
| `UmdErrorCode` 추가·변경 | architecture §7 + [ui-guide](docs/integration/ui-guide.md) 상태코드 표 |
| `umd.*` 설정 추가·기본값 변경 | architecture §11 + `application.yml` 주석 |
| 지표·health 추가·변경 | architecture §10 |
| 환경 변수 추가·변경 | `.env.*.example` + [dev-setup](docs/dev-setup.md) 또는 [deployment](docs/deployment.md) |
| 헤더 정책 변경 | architecture §6 + [ui-guide](docs/integration/ui-guide.md)·[backend-guide](docs/integration/backend-guide.md) |
| compose·Dockerfile·CI 변경 | [deployment](docs/deployment.md) |
| 의존성·명령어 변경 | 이 파일 §2·§3 |
| 규칙 예외 생성·해소 | [conventions](docs/conventions.md) §6 |
| 알려진 문제 발견·해결 | [known-issues](docs/known-issues.md) |
| 설계 결정 | [decisions](docs/decisions/) ADR + 이 파일 해당 절 |

`ContextDocsTest` 가 기계적으로 판별 가능한 불일치(링크, AGENTS/CLAUDE 짝, 지도, 클래스 표, 오류 코드, 설정 키, 지표, 환경 변수, 저장소 밖 링크)를 **빌드 실패**로 잡습니다 → [ADR 0001](docs/decisions/0001-context-docs.md).
설명이 맞는지는 판별하지 못하므로 위 표를 따르는 책임은 작업자에게 있습니다.

## 11. docs 색인 (필요할 때 읽기)

| 문서 | 언제 읽나 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 흐름·Stream 규격·헤더·오류·신뢰성·보안·관측·설정 |
| [docs/conventions.md](docs/conventions.md) | Java 코드 스타일, 네이밍, 알려진 예외 |
| [docs/dev-setup.md](docs/dev-setup.md) | JDK/Docker 준비, 로컬 실행, 환경 변수, Windows 주의 |
| [docs/deployment.md](docs/deployment.md) | CI, AP-01/Home PC 배포, Redis 보안, 롤백 |
| [docs/integration/ui-guide.md](docs/integration/ui-guide.md) | d94-web 이 지켜야 할 통신 규칙 |
| [docs/integration/backend-guide.md](docs/integration/backend-guide.md) | 로컬 백엔드가 지켜야 할 통신 규칙 |
| [docs/known-issues.md](docs/known-issues.md) | 남은 작업, 기술 부채 |
| [docs/decisions/](docs/decisions/) | 설계 결정 기록 (ADR) |
