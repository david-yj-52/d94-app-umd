# L2 · common (`tsh.d94.umd.common`, `.error`, `.spec`)

> 계층: [L1](../../../../../../../AGENTS.md) › **L2 common** · 활성 모드: 전부 (조건 없음)
> 상세: [architecture §4 Stream 규격 · §6 헤더 · §7 오류 · §11 설정](../../../../../../../docs/architecture.md)

## 역할

양 모드가 공유하는 것: 모드 판정, `umd.*` 설정, 헤더 정책, 오류 응답, 그리고 **server ↔ local 프로토콜인 Stream 규격**.

## 클래스

| 클래스 | 책임 | 관련 |
|---|---|---|
| `UmdMode` | `SERVER` / `LOCAL` / `BOTH` | `umd.mode` |
| `ConditionalOnUmdMode` + `OnUmdModeCondition` | 모드별 Bean 등록 조건. 미설정이면 SERVER | 모든 server/local Bean |
| `UmdProperties` | `umd.*` 바인딩·검증 (`Server`, `Local`, `Route` 하위 클래스) | `application.yml` |
| `UmdConfig` | `@EnableScheduling`, `@EnableConfigurationProperties` | |
| `UmdSettingsValidator` | `poll-timeout` < Redis 명령 타임아웃 검사, 어기면 기동 실패 | I9 |
| `StreamGroupInitializer` | 기동 시 Consumer Group `home-backend` 멱등 생성 (BUSYGROUP 무시) | 기동 순서 무관 |
| `ProxyHeaderPolicy` | 헤더 필터: `forRequest` / `withForwarded` / `forLocalDispatch` / `forResponse` | architecture §6 |
| `error.UmdErrorCode` | UMD 자체 오류 코드·상태, 헤더명 `X-Umd-Error` | architecture §7 |
| `error.UmdErrorResponse` | UMD 오류 응답의 헤더·`ApiResponse` 본문 생성 | |
| `spec.StreamKeys` | Redis 키·그룹·MAXLEN (`d94:umd:req`, `d94:umd:rep:{id}`, `d94:umd:local:alive`) | architecture §4 |
| `spec.StreamField` | 엔트리 필드명 상수 | |
| `spec.StreamHttpRequest` | server → local 요청. `fromFields` / `toFields`, `isExpired` | |
| `spec.StreamHttpReply` | local → server 응답 | |
| `spec.StreamBodyCodec` | 본문 ↔ 필드 값 (`utf8` / `base64`) | |
| `spec.StreamFieldSupport` | 필수 필드 읽기, 헤더 JSON, 본문 변환 공통 (package-private) | |

## 흐름 — 엔트리 변환

```
[server] HTTP 요청 → ProxyHeaderPolicy.forRequest → withForwarded → StreamHttpRequest.toFields → XADD
[local]  XREADGROUP → StreamHttpRequest.fromFields (필수 필드 없으면 IllegalArgumentException)
[local]  백엔드 응답 → ProxyHeaderPolicy.forResponse → StreamHttpReply.toFields → XADD replyTo
[server] XREAD → StreamHttpReply.fromFields → ProxyHeaderPolicy.forResponse → HTTP 응답
```

## 이 패키지의 불변식

- 엔트리는 **flat String Map**. 헤더는 소문자 키의 JSON 문자열, 본문은 `bodyEncoding` 에 따라 utf8 또는 base64.
- `fromFields` 는 **모르는 필드를 무시**하고, **필수 필드가 없으면 거부**합니다 (local: 폐기·ACK / server: 502 `BAD_REPLY` 또는 폐기).
- `common` 은 server/local 패키지를 import 하지 않습니다.

### Stream 규격 변경 체크리스트 (L1 I5)

server(AP-01)와 local(Home PC)은 **따로 배포**되므로 한동안 버전이 다를 수 있습니다.

1. **필드 추가**는 선택 필드로만. 읽는 쪽은 없을 때의 기본 동작을 가져야 합니다 (모르는 필드는 원래 무시됨).
2. **필수 필드 추가·삭제·이름/의미 변경**은 호환 불가 → ADR 작성, 배포 순서(또는 동시 배포)를 [deployment §6](../../../../../../../docs/deployment.md) 에 명시.
3. `StreamHttpMessageTest` 에 왕복·누락 케이스 추가.
4. [architecture §4](../../../../../../../docs/architecture.md) 필드 표 갱신.

## 주의 · 예외

- 오류 코드를 추가하면 `StreamProxyService.classify` / `LocalHttpDispatcher` 매핑과 지표 `outcome` 값도 함께 바뀝니다.
- 설정 기본값은 `UmdProperties` 필드 초기값과 `application.yml` 두 곳에 있습니다. 둘을 같이 고칩니다.
