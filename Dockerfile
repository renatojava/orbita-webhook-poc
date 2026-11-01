FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp -q dependency:go-offline || true
COPY src src
RUN ./mvnw -B -ntp -q -DskipTests package && cp target/orbita-webhook-poc-*.jar app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER 1000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
