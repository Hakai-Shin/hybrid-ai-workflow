FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tesseract-ocr curl
WORKDIR /app
COPY --from=builder /app/target/enclave-*.jar app.jar
VOLUME ["/data", "/secrets"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
