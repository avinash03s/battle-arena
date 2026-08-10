# Stage 1: Build the application
FROM maven:3.8.3-openjdk-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:17-jdk
WORKDIR /app

# Safely locate the generated executable jar and rename it
COPY --from=build /app/target/spring-ai-0.0.1-SNAPSHOT.jar app.jar

# Force Spring Boot to listen to Render's dynamic port via an Environment variable
ENV SERVER_PORT=8080

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
