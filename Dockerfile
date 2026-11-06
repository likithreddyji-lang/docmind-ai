# Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy dependency definition to leverage Docker cache
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies
RUN mvn dependency:go-offline -B || ./mvnw dependency:go-offline -B || true

# Copy source code and package application
COPY src src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a non-root application user for security
RUN addgroup -S docmind && adduser -S docmind -G docmind
USER docmind

# Copy the built jar from builder
COPY --from=builder /build/target/docmind-backend-0.0.1-SNAPSHOT.jar app.jar

# Render supplies PORT env var dynamically
ENV PORT=8081
EXPOSE ${PORT}

# Optimize JVM for Render's free tier (512MB RAM)
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

