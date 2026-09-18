# Build context is the repository root (the control plane inherits the root pom).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY .mvn .mvn
COPY apps/control-plane/pom.xml apps/control-plane/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q -f apps/control-plane/pom.xml dependency:go-offline
COPY apps/control-plane/src apps/control-plane/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q package -DskipTests -Dspotless.check.skip=true && cp apps/control-plane/target/control-plane-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
