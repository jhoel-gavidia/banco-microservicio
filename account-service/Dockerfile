# syntax=docker/dockerfile:1
FROM amazoncorretto:21-alpine3.24@sha256:600e99e39da14b6a909027a924136c423e9334cb7347a8a77d5908bdaa3011b7 AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted

FROM amazoncorretto:21-alpine3.24@sha256:600e99e39da14b6a909027a924136c423e9334cb7347a8a77d5908bdaa3011b7 AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./

USER app
EXPOSE 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
