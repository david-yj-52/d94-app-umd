# ADR 0003: Stream 규격을 lib-core 가 아닌 UMD 내부에 둔다

- 상태: 채택
- 날짜: 2026-10-25
- 출처: `note/feature/rest api/01-development-plan.md` §3-0

## 배경
init 단계는 Home PC 백엔드와 공유한다는 전제로 규격을 `d94-lib-core` 에 두었습니다. 투명 프록시 구조([ADR 0002](0002-transparent-rest-proxy.md))에서는 Stream 을 읽고 쓰는 것이 UMD 두 모드뿐입니다.

## 결정
- 규격은 `tsh.d94.umd.common.spec` 에 둡니다. lib-core 의 init 변경분은 되돌렸습니다.
- 재검토 조건: UMD 가 아닌 애플리케이션이 Stream 을 직접 읽고 쓰게 될 때 lib-core 로 승격합니다.

## 결과
- 규격 변경 시 lib-core 배포 → UMD 빌드 순서가 사라지고 UMD 단독 빌드·배포가 가능합니다.
- 대신 server 와 local 이 따로 배포되므로 규격 변경은 프로토콜 변경으로 다룹니다 (L1 불변식 I5).
