# 개발 환경

## 요구사항

| 항목 | 내용 |
|---|---|
| JDK 21 | `JAVA_HOME` 지정 필요. IntelliJ 가 내려받은 JDK(`~/.jdks/…`)도 사용 가능 |
| Docker | 통합 테스트(Testcontainers `redis:7-alpine`)와 compose 실행용. **없으면 통합 테스트가 skip 됨** |
| Maven | 설치 불필요 — `./mvnw` 가 첫 실행 때 Maven 3.9.9 를 `~/.m2/wrapper` 에 내려받음 |
| parent pom · lib-core | workspace 에서 `../../lib/d94-lib-pom` 을 찾고, 없으면 GitHub Packages(`github-d94`, `github-lib-core`)에서 받음 → `~/.m2/settings.xml` 에 `read:packages` PAT 필요 |

## 빌드 · 테스트

```bash
export JAVA_HOME=~/.jdks/jbr-21.0.11   # 예시. 자기 PC 의 JDK 21 경로
./mvnw -B clean verify
```

- 결과 확인: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` 과 `BUILD SUCCESS`. **Skipped 가 0 이 아니면 Docker 를 확인하세요.**
- 단일 테스트: `./mvnw -B test -Dtest=StreamProxyServiceTest`
- Windows PowerShell 에서는 `.\mvnw.cmd -B clean verify`.

## 로컬 실행 (both 모드, 한 프로세스에서 server + local)

```bash
docker run -d --name umd-redis -p 6379:6379 redis:7-alpine
./mvnw -B -DskipTests package
UMD_MODE=both JWT_SECRET_KEY=<d94-query 와 같은 Base64 키> \
  java -jar target/d94.app.umd-1.0.0-SNAPSHOT.jar \
  --umd.local.routes[0].id=default --umd.local.routes[0].path-prefix=/ --umd.local.routes[0].uri=http://localhost:8080
```

- 앱 포트 8081 → Redis → 로컬 백엔드(`localhost:8080`). actuator 는 `localhost:8091/actuator/health`.
- UI 를 붙이려면 d94-web `.env.local` 의 `BACKEND_URL=http://localhost:8081` → [integration/ui-guide.md](integration/ui-guide.md)
- 배포 형태(server/local 컨테이너 분리)는 [deployment.md](deployment.md)

## 환경 변수

| 변수 | 사용처 | 설명 |
|---|---|---|
| `UMD_MODE` | `umd.mode` | `server`(기본) / `local` / `both` |
| `UMD_INSTANCE_ID` | `umd.instance-id` | server: 응답 Stream 이름, local: Consumer 이름. 인스턴스마다 고유. 없으면 `HOSTNAME` |
| `HOSTNAME` | `umd.instance-id` 기본값 | 둘 다 없으면 `umd-local` |
| `APP_PORT` | `server.port` | 앱 포트 (기본 8081) |
| `MANAGEMENT_PORT` | `management.server.port` | actuator 포트 (기본 8091) |
| `APP_ENV` | `/actuator/info` | 환경 표시 (기본 `LOCAL`) |
| `UMD_REQUEST_TIMEOUT` | `umd.server.request-timeout` | server 응답 대기 (기본 30s) |
| `UMD_CORS_ORIGINS` | `umd.server.cors-origins` | UMD 자체 오류 응답에 붙일 Origin (쉼표 구분) |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL_ENABLED` | `spring.data.redis.*` | Redis 접속 |
| `SPRING_DATA_REDIS_USERNAME` | Spring 기본 바인딩 | Redis ACL 사용자 (없으면 `default`) |
| `JWT_SECRET_KEY` | `application.security.jwt.secret-key` | **server·both 모드만 필수.** d94-query 와 같은 Base64 키 |

compose 전용 변수(`IMAGE_TAG`, `UMD_IMAGE`, `MANAGEMENT_BIND`, `MANAGEMENT_HOST_PORT`)는 [deployment.md](deployment.md) 에 있습니다.
새 변수를 추가하면 `.env.*.example` 과 이 표(또는 deployment.md)를 함께 갱신하세요.

## 주의 (Windows)

- **Git Bash 경로 변환:** docker 명령에 `/` 로 시작하는 값을 넘기면 `C:/Program Files/Git/…` 로 바뀝니다 → `MSYS_NO_PATHCONV=1` 을 붙이거나 routes 파일을 쓰세요.
- **콘솔 한글 깨짐:** Git Bash 에서 `./mvnw` 출력의 한글 로그가 `???` 로 보입니다. 테스트 결과와는 무관합니다 → [known-issues.md](known-issues.md)
- 테스트 실행 시 로그 파일이 `logs/` 에 쌓입니다 (`*.log` 는 git 제외).
