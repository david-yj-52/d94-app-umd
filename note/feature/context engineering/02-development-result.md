# [context engineering] AI 컨텍스트 문서 체계 도입 — 개발 결과

| 항목 | 내용 |
|---|---|
| 계획 문서 | `01-development-plan.md` |
| 범위 | 0~5단계 전체 |
| 결정 | D1~D4 권장안 그대로 (빌드 차단 / 배포 문서 이동 / note 유지 / `type: 설명`) |
| 작성일 | 2026-10-25 |
| 상태 | 완료, **미커밋** |

---

## 1. 결과 요약

- 루트 `AGENTS.md`(L1, 148줄)와 L2 4개(`common`, `server`, `local`, test) + 각 `CLAUDE.md`(`@AGENTS.md`).
- 현재 상태 문서를 `note/` 에서 `docs/` 로 분리, `note/` 는 이력으로 표시하고 기본 로드에서 제외.
- 불변식 10개(I1~I10)를 L1 §5 에 정리 — 항상 로드됨.
- Maven Wrapper 로 셸에서 `./mvnw -B clean verify` 가능. **134건 통과, Skipped 0**.
- `ContextDocsTest` 가 문서-코드 불일치를 빌드 실패로 잡음.

## 2. 단계별 결과

### 0단계 — 검증 루프

| 항목 | 결과 |
|---|---|
| Maven Wrapper | `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (wrapper 3.3.2, only-script, Maven 3.9.9). IntelliJ 번들 Maven 으로 생성 |
| JDK | PATH 에 없음 → `JAVA_HOME` 지정 필요 (이 PC: `~/.jdks/jbr-21.0.11`). [dev-setup](../../../docs/dev-setup.md) 에 기록 |
| 기준선 | 변경 전 `./mvnw -B clean verify` → 126건, Skipped 0, BUILD SUCCESS (Docker 29.8.2) |

### 1단계 — 골격

| 항목 | 결과 |
|---|---|
| L1 | `AGENTS.md` + `CLAUDE.md` — 지도, 요약, 스택, 명령어, 불변식, 금지, 작업 절차, 체크리스트, 커밋 규칙, 동기화 매핑표, docs 색인 |
| 이동 (`git mv`) | `note/architecture/umd-architecture.md` → `docs/architecture.md`, `ref/ui-development-guide.md` → `docs/integration/ui-guide.md`, `ref/backend-development-guide.md` → `docs/integration/backend-guide.md` |
| 복사 | workspace `note/process/deploy/umd.md` → `docs/deployment.md` (원본은 4단계에서 링크로 교체 예정) |
| 신규 | `docs/conventions.md`, `docs/dev-setup.md`, `docs/known-issues.md`, `note/README.md` |

### 2단계 — L2 · ADR · 양식

| 항목 | 결과 |
|---|---|
| L2 | 클래스 표(코드의 Javadoc 기준), 흐름, 패키지 불변식, 주의. `common` 에 Stream 규격 변경 체크리스트 |
| ADR | 0001 문서 체계, 0002 투명 REST 프록시, 0003 규격 위치, 0004 서블릿 필터, 0005 X-Forwarded-For + `decisions/README.md` |
| 양식 | `docs/templates/feature-plan.md`, `feature-result.md` |

### 3단계 — `ContextDocsTest`

| 검사 | 기준 |
|---|---|
| 링크 | 모든 `.md` 상대 링크 대상 존재, **저장소 밖 경로 금지** |
| 짝 | `AGENTS.md` 옆 `CLAUDE.md` 에 `@AGENTS.md` |
| 지도 | 모든 L2 가 L1 §0 에 등록 |
| 클래스 | `src/main/java` 의 클래스가 가장 가까운 `AGENTS.md` 에 등장 |
| 오류 코드 | `UmdErrorCode` 의 `UMD_*` → `docs/architecture.md` |
| 설정 | `UmdProperties` 필드(kebab-case) → `docs/architecture.md` |
| 지표 | `Counter/Timer/Gauge.builder("umd.…")` → Prometheus 이름(`_total`/`_seconds`) → `docs/architecture.md` |
| 환경 변수 | `.env.*.example` 키 → `docs/dev-setup.md` 또는 `docs/deployment.md` |

- 검출 확인: 오류 코드·설정 키·지표·클래스를 문서에서 지우고, 끊긴 링크·저장소 밖 링크를 넣었을 때 5개 검사가 모두 실패함을 확인 후 원복.

### 4단계 — 교차 저장소

| 저장소 | 변경 |
|---|---|
| d94-web (미커밋) | `docs/architecture.md` 요청 흐름에 UMD 홉·`X-Umd-Error`·ui-guide 링크, `src/lib/AGENTS.md` §4 백엔드 경로·ui-guide 링크, `docs/known-issues.md` 프록시 라우트 미정비 항목. `npm run docs:check` 오류 0. 기존 미커밋 변경(배포 절의 vercel.json 설명, `vercel.json`)은 건드리지 않음 |
| workspace (git 아님) | 루트 `AGENTS.md`(저장소 지도·요청 경로·저장소 간 규칙) + `CLAUDE.md`. `note/process/deploy/umd.md` 를 UMD `docs/deployment.md` 링크로 교체 (원본 사본은 세션 scratchpad 에 보관) |

- 저장소 간 링크는 GitHub URL (`…/d94-app-umd/blob/main/docs/…`) → UMD `docs/` 가 main 에 push 되기 전까지 404.

### 5단계 — 효과 검증

새 컨텍스트의 에이전트에게 Markdown(L1 → L2 → docs)만 읽게 하고(코드·설정·`note/` 금지) 계획 §4 의 질문 6개를 물음.

| 질문 | 결과 |
|---|---|
| 1 새 로컬 서비스 | 정답 — routes 한 줄, 코드 변경 없음 |
| 2 Stream 필드 추가 | 정답 — 선택 필드·기본 동작·테스트·문서. 단 ADR 필요 여부에서 문서 간 모순 지적 |
| 3 server 전용 Bean | 정답 — `@ConditionalOnUmdMode({SERVER, BOTH})`, local JWT 키 없는 기동 확인 |
| 4 회수된 만료 POST | 정답 — deadline 먼저 확인, 호출 없이 ACK, 중복 실행 방지 |
| 5 테스트 통과 보고 | 정답 — `./mvnw -B clean verify` + Skipped 0 |
| 6 503 원인 구분 | 정답 — `X-Umd-Error` 4종. d94-web 이 헤더를 버린다는 점까지 짚음 |

- 읽은 분량: Markdown 13개, 약 1,130줄 (실제 필요한 것은 L1·L2 3개·architecture·deployment·ui-guide).
- 지적된 빈틈을 코드로 확인한 뒤 보완:

| 지적 | 보완 |
|---|---|
| L1 §10 "필드 변경 → ADR" vs I5 "삭제·의미 변경만 ADR" 모순 | §10 을 "호환 불가일 때 ADR + 배포 순서" 로 |
| deployment §6 "형식이 바뀌면 함께 올린다" vs I5 모순 | 하위 호환은 순서 무관, 호환 불가는 ADR 순서 또는 동시 |
| 필수/선택 필드 구분 없음 | architecture §4 표에 필수·선택 표시 + 모르는 필드 무시 규칙 |
| routes 반영 방식 없음 | deployment §4·local L2: 기동 시에만 읽음 → 재기동, 마운트 방식 |
| `umd_proxy_rejected_total{reason}` 값 목록 없음 | architecture §10 에 `UmdErrorCode` 이름 4종 |
| server 흐름의 예외 → 코드 매핑 불명확 | server L2 흐름에 코드명 명시 |
| local 비정상 종료 직후 503 대신 504 가능 구간 | architecture §8, ui-guide §4 에 약 16초 구간 |
| actuator 8091/8092 혼동 | L1 §2, deployment §4 에 컨테이너/호스트 포트 구분 |
| 백엔드 호출 도중 local 사망 시 at-least-once | local L2 주의 + backend-guide §6 링크 |

## 3. 테스트 결과

`./mvnw -B clean verify` → **Tests run: 134, Failures: 0, Errors: 0, Skipped: 0**, BUILD SUCCESS (기존 126 + `ContextDocsTest` 8).

## 4. 작업 중 발견·수정한 문서 드리프트

| 문제 | 조치 |
|---|---|
| `UMD_INTERNAL`(500) 이 아키텍처 오류 표에 없음 | `docs/architecture.md` §7 에 추가 |
| 배포 문서가 "main push → 이미지 푸시" 로 설명 | 실제 workflow 는 `deploy` 브랜치·`v*` 태그 → `docs/deployment.md` 수정 |
| `latest` 태그는 main 일 때만 붙는데 workflow 는 main 에서 돌지 않음, compose 기본값은 `latest` | `docs/known-issues.md` 등록 (코드 미수정, 결정 필요) |
| init·rest api 이력 문서의 폐기 설계·"미커밋"·`NEXT_PUBLIC_API_URL`·`/api/...` | 본문 유지, `note/README.md` 에 바뀐 사실 기록 |
| 모드 전용 클래스 10개의 Javadoc `[server]`/`[local]` 접두사 누락 | `docs/conventions.md` §6 예외로 등록 (코드 미수정) |

## 5. 계획 대비 변경 사항

| 계획 | 실제 | 이유 |
|---|---|---|
| 배포 문서 "이동" | 복사 (workspace 원본 유지) | workspace 수정은 4단계 범위 |
| `ContextDocsTest` 의 지표 검사: 코드의 `umd_*` 문자열 | Micrometer 점 표기 이름에서 Prometheus 이름 유도 | 코드에는 `umd.local.inflight` 형태만 있음 |
| (추가) | `docs/decisions/README.md` ADR 색인 | |

## 6. 갱신한 문서

`AGENTS.md`, `CLAUDE.md`, L2 `AGENTS.md`·`CLAUDE.md` 4쌍, `docs/architecture.md`(링크·`UMD_INTERNAL`), `docs/integration/{ui,backend}-guide.md`(링크), `docs/deployment.md`(CI 트리거), `docs/{conventions,dev-setup,known-issues}.md`, `docs/decisions/*`, `docs/templates/*`, `note/README.md`

## 7. 남은 작업

| 항목 | 비고 |
|---|---|
| 커밋 | UMD·d94-web 모두 미커밋. UMD `.gitignore` 의 `logs` 추가는 이 작업이 아님 — 함께 커밋할지 확인 |
| push | UMD `docs/` 가 main 에 push 돼야 d94-web·workspace 의 GitHub 링크가 유효 |
| `latest` 태그 문제 | `docs/known-issues.md` — workflow 조건 또는 compose 기본값 결정 |
| d94-web 프록시 라우트 정비 | 코드 작업 (ui-guide §5), d94-web `known-issues` 에 등록됨 |
