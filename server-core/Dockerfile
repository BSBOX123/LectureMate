# 1단계: Gradle 빌드 (의존성 캐시를 위해 설정 파일을 먼저 복사)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# 2단계: 실행 (JRE 만)
FROM eclipse-temurin:21-jre
WORKDIR /app

# root 로 실행하지 않는다
RUN useradd --system --create-home --uid 10001 lecturemate
USER lecturemate

COPY --from=build /build/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
