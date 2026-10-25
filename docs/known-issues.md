# 알려진 문제 · 남은 작업

발견 시점: 2026-10-25. 해결하면 목록에서 지우고, 설계 결정이 따르면 ADR 을 남기세요.

## 배포 · 운영

- **`latest` 이미지 태그가 만들어지지 않음:** `.github/workflows/deploy.yml` 은 `deploy` 브랜치 push·PR 과 `v*` 태그에서만 실행되는데, `latest` 태그는 `refs/heads/main` 일 때만 붙습니다. 반면 compose 기본값은 `IMAGE_TAG=latest` 입니다. → 지금은 `.env` 에 `sha-…` 또는 버전 태그를 지정해야 합니다. 조건을 `deploy` 로 바꾸거나 compose 기본값을 바꾸는 결정이 필요합니다.
- **AP-02 Redis 보안 미적용:** TLS, ACL 사용자(`umd-server`, `umd-local`), 방화벽 → [deployment.md](deployment.md) §2
- **phase1 아키텍처 문서 보정 제안:** Zuul → Local UMD 라우팅, Redis Insight 포트 15672 → 5540 (workspace 문서).

## 연동

- **GitHub URL 링크는 push 후 유효:** d94-web 문서와 workspace 문서가 `github.com/david-yj-52/d94-app-umd/blob/main/docs/...` 를 가리킵니다. `docs/` 가 main 에 push 되기 전까지는 404 입니다.

- **d94-web 프록시 라우트 정비:** PATCH/HEAD, 응답 상태·헤더 그대로 전달, 35초 타임아웃 → [integration/ui-guide.md](integration/ui-guide.md) §5
- **Local 백엔드:** [integration/backend-guide.md](integration/backend-guide.md) 체크리스트 준수 여부 미확인 (JWT 직접 검증, `forward-headers-strategy`, Trace ID 필터).

## 후속 검토

- 사용자별 rate limit (현재는 대기 요청 상한 503 만)
- 대용량 파일 (presigned URL), SSE·WebSocket 미지원
- Redis Sentinel · 다중 server (Phase 2)

## 도구 · 품질

- **통합 테스트 조용한 skip:** `@Testcontainers(disabledWithoutDocker = true)` 라 Docker 가 없으면 실패 대신 skip 됩니다. 결과의 `Skipped` 수를 확인해야 합니다.
- **콘솔 한글 깨짐:** Windows Git Bash 에서 `./mvnw` 실행 시 한글 로그가 `???` 로 출력됩니다 (JVM 콘솔 인코딩).
- **포맷터 설정 없음:** IntelliJ 포맷에 의존합니다 (`.editorconfig` 없음).
