# note/ — 개발 이력

> **이력 문서입니다. 현재 상태가 아닙니다.** 현재 상태는 [AGENTS.md](../AGENTS.md) 와 [docs/](../docs/) 를 보세요.
> 내용이 서로 다르면 **코드 > docs/ > note/** 순으로 믿습니다. AI 도구는 사용자가 지시할 때만 이 폴더를 읽습니다.

이력 문서는 당시 기록이므로 고치지 않습니다. 작성 이후 바뀐 사실은 아래 표에만 적습니다.

| 기능 | 문서 | 상태 | 작성 이후 바뀐 사실 |
|---|---|---|---|
| init | `feature/init/01·02` | **폐기됨** | eventNm 태스크 게이트웨이와 lib-core Stream 규격은 rest api 단계에서 전부 제거됨 ([ADR 0002](../docs/decisions/0002-transparent-rest-proxy.md), [ADR 0003](../docs/decisions/0003-stream-spec-in-umd.md)) |
| rest api | `feature/rest api/01·02` | 완료 | "미커밋" → `ff908d2` 로 커밋됨. 계획의 `NEXT_PUBLIC_API_URL`·`/api/...` 예시 → 실제는 `BACKEND_URL`·`/v1/...`. `ref/` 가이드는 [docs/integration/](../docs/integration/) 으로 이동 |
| context engineering | `feature/context engineering/01·02` | 완료 (미커밋) | — |

`architecture/umd-architecture.md` 는 [docs/architecture.md](../docs/architecture.md) 로 이동했습니다.

## 새 기능 기록

[AGENTS.md §7](../AGENTS.md) 절차에 따라 `feature/{기능}/01-development-plan.md` → `02-development-result.md` 를 만들고, 끝나면 `docs/` 에 현재 상태를 반영합니다.
양식: [feature-plan](../docs/templates/feature-plan.md), [feature-result](../docs/templates/feature-result.md)
