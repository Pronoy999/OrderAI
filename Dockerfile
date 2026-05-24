# ==========================================
# Stage 1: Build compilation fat JAR
# ==========================================
FROM maven:3.8.8-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy parent pom.xml and child pom.xml to optimize docker caching
COPY pom.xml .
COPY order-parser/pom.xml ./order-parser/
RUN mvn dependency:go-offline -B

# Copy child source code and compile
COPY order-parser/src ./order-parser/src
RUN mvn clean package -DskipTests

# ==========================================
# Stage 2: Runtime JRE environment
# ==========================================
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the built fat JAR from the builder stage child target folder
COPY --from=builder /app/order-parser/target/order-parser-1.0-SNAPSHOT-jar-with-dependencies.jar ./order-ai.jar

# Run the jar as daemon entrypoint
ENTRYPOINT ["java", "-jar", "order-ai.jar"]
