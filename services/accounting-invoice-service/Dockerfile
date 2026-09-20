FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY src src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/accounting-invoice-service-0.1.0.jar app.jar
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
