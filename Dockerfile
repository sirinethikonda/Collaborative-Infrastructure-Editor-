# Build Stage
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build

COPY pom.xml .
COPY cmd/ cmd/
COPY internal/ internal/
COPY validation/ validation/
COPY src/ src/

RUN mvn clean package -DskipTests

# Runtime Stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install curl, ca-certificates, and kubectl
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && curl -LO "https://dl.k8s.io/release/v1.28.2/bin/linux/amd64/kubectl" \
    && chmod +x kubectl \
    && mv kubectl /usr/local/bin/ \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/infra-editor-1.0.0.jar app.jar

ENV APP_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
