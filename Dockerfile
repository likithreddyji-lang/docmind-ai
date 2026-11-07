# Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Copy Maven wrapper & POM to leverage Docker caching
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies in offline mode
RUN mvn dependency:go-offline -B || ./mvnw dependency:go-offline -B || true

# Copy source code and package application jar
COPY src src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a non-root application user for container security
RUN addgroup -S docmind && adduser -S docmind -G docmind
USER docmind

# Copy the built jar from builder stage
COPY --from=builder /build/target/docmind-backend-0.0.1-SNAPSHOT.jar app.jar

# Render supplies PORT dynamically at runtime (defaulting to 8081 locally)
ENV PORT=8081
EXPOSE ${PORT}

# Memory-optimized JVM flags for Render's free tier (512MB RAM):
# - MaxRAMPercentage=70.0: caps heap at ~358MB, reserving room for Metaspace and OS threads
# - UseSerialGC: minimal GC memory overhead compared to G1GC
# - Xss512k: reduces thread stack size to conserve memory
# - ExitOnOutOfMemoryError: triggers clean container restart on OOM
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=70.0", \
  "-XX:+UseSerialGC", \
  "-Xss512k", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
