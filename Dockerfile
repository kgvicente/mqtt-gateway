FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# ponytail: compilar una sola vez y conservar la caché Maven entre builds.
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -ntp package -DskipTests

FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0
WORKDIR /app
RUN mkdir -p /app/data && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/target/quarkus-app/ /app/
COPY LICENSE NOTICE /app/
COPY licenses/ /app/licenses/
USER 10001:10001
ENV QUARKUS_HTTP_HOST=0.0.0.0 GATEWAY_DATA_DIR=/app/data
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
