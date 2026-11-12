# ---- Build stage: compile and package the Spring Boot fat jar with the Maven wrapper ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Wrapper and POM first so the dependency layer is cached until pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# The repo may be checked out on Windows: strip CRLF and restore the exec bit.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -q package -DskipTests

# ---- Runtime stage: JRE only. Ubuntu (glibc) because OR-Tools ships glibc native libraries; NOT alpine ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run as an unprivileged user. OR-Tools extracts its native libraries to java.io.tmpdir (/tmp),
# which is world-writable, so no extra permissions are needed.
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --create-home --home-dir /home/app --shell /usr/sbin/nologin app

# Spring Boot repackages to dispatch-load-balancer-<version>.jar (the plain jar is *.jar.original,
# which this pattern does not match).
COPY --from=build /app/target/dispatch-load-balancer-*.jar /app/app.jar

USER app
EXPOSE 8080

# Heap = 75% of the container memory limit. server.port=${PORT:8080} picks up Render's PORT.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
# sh -c so $JAVA_OPTS is expanded at start-up; exec so java is PID 1 and receives SIGTERM.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
