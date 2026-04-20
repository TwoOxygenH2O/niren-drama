FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN apk add --no-cache ffmpeg
RUN addgroup -S niren && adduser -S niren -G niren
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/uploads && chown -R niren:niren /app
USER niren
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
