FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw --batch-mode dependency:go-offline

COPY src src
RUN ./mvnw --batch-mode clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
RUN mkdir -p /app/uploads/products && chown -R spring:spring /app
COPY --from=build --chown=spring:spring /workspace/target/*.jar app.jar
USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
