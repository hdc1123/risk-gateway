FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B verify dependency:copy-dependencies -DincludeScope=runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/classes /app/classes
COPY --from=build /build/target/dependency /app/lib
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "com.risk.gateway.RiskApplication"]
