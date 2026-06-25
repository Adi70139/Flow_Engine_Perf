FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

EXPOSE 8070

ENTRYPOINT ["java", "-jar", "target/perf-service-1.0.0.jar"]