# ADR 0002: eventNm 태스크 게이트웨이를 폐기하고 투명 REST 프록시로

- 상태: 채택 (init 단계 설계를 대체)
- 날짜: 2026-10-25
- 출처: `note/feature/rest api/01-development-plan.md` §1

## 배경
- init 단계의 UMD 는 `POST /api/v1/tasks/{eventNm}` 형태의 태스크 게이트웨이였습니다. 새 업무마다 이벤트 화이트리스트와 UMD 변경이 필요했습니다.
- Phase 1 아키텍처가 Redis 양쪽에 UMD 를 두는 구조로 바뀌었습니다.

## 결정
- UMD 는 HTTP ↔ Redis Stream **양방향 변환기** 하나로, 메서드·경로·쿼리·헤더·본문·상태를 그대로 나릅니다.
- 같은 jar 를 `umd.mode` 로 `server`/`local`(개발용 `both`)로 실행합니다.
- 최종 목표는 UMD·Redis 를 빼고 UI 의 `BACKEND_URL` 만 바꾸면 직결되는 것입니다 (context-path 없음, 표준 헤더만, 인증·CORS 는 백엔드 소유, 오류는 상태코드).

## 결과
- 새 API 는 백엔드만 배포, 새 로컬 서비스는 `umd.local.routes` 한 줄입니다.
- eventNm 관련 코드는 모두 삭제했습니다. 특수 명령 모드가 필요해지면 별도 기능으로 다룹니다.
- 스트리밍(SSE·WebSocket), 1MB 넘는 요청 / 5MB 넘는 응답, 30초 넘는 동기 처리는 지원하지 않습니다.
