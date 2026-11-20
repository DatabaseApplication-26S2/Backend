# syntax=docker/dockerfile:1

# 1단계: Gradle 래퍼로 실행 JAR을 만든다.
# 테스트는 Testcontainers(Docker)가 필요하므로 이미지 빌드에서는 돌리지 않는다. 테스트는 ./gradlew build redisTest로 따로 돌린다.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src/main src/main
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
 && ./gradlew --no-daemon bootJar \
 && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar')" /app.jar

# 2단계: JRE만 담은 실행 이미지. 비밀 값(JWT 서명 키, DB 비밀번호)은 이미지에 넣지 않고 실행 시 환경 변수로 받는다.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S crs && adduser -S -G crs crs
WORKDIR /app
COPY --from=build /app.jar app.jar
USER crs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
