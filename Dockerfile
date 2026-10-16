# Multi-platform OCI indexes verified from the official Docker Hub registry.
ARG TEMURIN_JDK_IMAGE=eclipse-temurin:21.0.12.1_1-jdk-alpine-3.24@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159
ARG TEMURIN_JRE_IMAGE=eclipse-temurin:21.0.12.1_1-jre-alpine-3.24@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555

# The Spring Boot JAR is architecture-independent, so compile on the native
# BuildKit host and only select the target architecture for the runtime image.
FROM --platform=$BUILDPLATFORM ${TEMURIN_JDK_IMAGE} AS build
WORKDIR /workspace
ARG MAVEN_CACHE_ID=tcg-maven

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,id=${MAVEN_CACHE_ID},target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src src
ARG SOURCE_DATE_EPOCH=1704067200
RUN --mount=type=cache,id=${MAVEN_CACHE_ID},target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests \
    -Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH} \
    && set -- target/*.jar \
    && test "$#" -eq 1 \
    && cp "$1" /workspace/app.jar \
    && touch -d "@${SOURCE_DATE_EPOCH}" /workspace/app.jar

FROM ${TEMURIN_JRE_IMAGE} AS runtime
ARG SOURCE_DATE_EPOCH=1704067200
ARG OCI_SOURCE=https://github.com/mudst-2026-luntungsadungyong/tcg-store-backend
ARG OCI_REVISION=unknown
ARG OCI_VERSION=0.0.1-SNAPSHOT
ARG OCI_CREATED=unknown
ARG OCI_LICENSES=UNLICENSED
ARG OCI_TITLE="TCG Store Backend"
ARG OCI_DESCRIPTION="TCG Store backend API and database migration runner"

LABEL org.opencontainers.image.source="${OCI_SOURCE}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.created="${OCI_CREATED}" \
      org.opencontainers.image.licenses="${OCI_LICENSES}" \
      org.opencontainers.image.title="${OCI_TITLE}" \
      org.opencontainers.image.description="${OCI_DESCRIPTION}"

RUN addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && touch -d "@${SOURCE_DATE_EPOCH}" /etc/passwd /etc/group /etc/shadow
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
