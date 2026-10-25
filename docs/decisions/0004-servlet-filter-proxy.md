# ADR 0004: server 프록시를 MVC 컨트롤러가 아닌 서블릿 필터로

- 상태: 채택
- 날짜: 2026-10-25
- 출처: `note/feature/rest api/02-development-result.md` §4

## 배경
계획은 `@RequestMapping("/**")` 컨트롤러였습니다. 그러나 Spring MVC 는 CORS preflight(OPTIONS + Origin + Access-Control-Request-Method)를 핸들러에 넘기지 않고 직접 응답합니다. CORS 는 백엔드 소유이므로 preflight 도 그대로 전달해야 합니다.

## 결정
- `StreamProxyFilter`(OncePerRequestFilter)를 Security 필터 뒤, DispatcherServlet 앞에 등록하고 AsyncContext 로 응답을 기다립니다 (`StreamProxyConfig`).
- PUT/PATCH form 본문을 미리 읽는 `FormContentFilter` 를 끕니다 (`spring.mvc.formcontent.filter.enabled=false`).
- 메시지 컨버터 없이 바이트 그대로 주고받습니다.

## 결과
- MVC 예외 처리기·컨트롤러 기반 기능(Swagger 등)을 쓰지 않습니다.
- 필터 순서나 `FormContentFilter` 설정을 바꾸면 preflight 전달·본문 보존이 깨집니다 (`ServerProxyIntegrationTest`, `EndToEndBothModeTest` 가 검증).
