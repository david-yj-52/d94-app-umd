# ADR 0005: X-Forwarded-For 는 덮어쓰지 않고 이어 붙인다

- 상태: 채택
- 날짜: 2026-10-25
- 출처: `note/feature/rest api/02-development-result.md` §4

## 배경
계획은 `X-Forwarded-*` 를 UMD 가 덮어쓰는 것이었습니다. 그러나 UI 요청은 브라우저 → Next.js 서버(`/api/proxy`) → UMD 순서로 오므로, 덮어쓰면 Next.js 가 넣은 브라우저 IP 가 사라집니다.

## 결정
- `X-Forwarded-For` 는 기존 체인 뒤에 직전 홉 주소를 이어 붙입니다 (표준 동작).
- `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Trace-Id` 는 덮어씁니다.

## 결과
- 백엔드는 `server.forward-headers-strategy` 로 원래 클라이언트 정보를 얻을 수 있습니다 ([backend-guide](../integration/backend-guide.md)).
- 구현: `ProxyHeaderPolicy.withForwarded`, 검증: `ProxyHeaderPolicyTest`.
