# Build stage: compile the Spring Boot backend with the Maven wrapper.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY backend/src src
RUN ./mvnw -q package -DskipTests

# Run stage: JRE only, frontend mounted where the backend expects it (../docs).
FROM eclipse-temurin:21-jre
COPY docs /docs
WORKDIR /app
COPY --from=build /build/target/replay-trainer-*.jar app.jar
ENV SPRING_PROFILES_ACTIVE=cloud
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
