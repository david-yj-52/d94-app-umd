FROM eclipse-temurin:21-jre-alpine

# 비root 실행
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/*.jar app.jar
RUN mkdir -p /app/logs && chown -R app:app /app
USER app

# 8081: API (외부 공개) / 8091: actuator (내부 전용, health·prometheus)
EXPOSE 8081 8091

# 컨테이너 메모리 한도 기준으로 힙 설정, OOM 시 즉시 종료 -> restart 정책으로 재기동
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8091/actuator/health > /dev/null || exit 1

# exec: java 가 PID 1 이 되어 SIGTERM 을 받아야 graceful shutdown 이 동작한다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
