# Runtime image only. Build the jar first: ./mvnw -B package -DskipTests
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 aiws && mkdir -p /var/lib/aiws/assets && chown aiws /var/lib/aiws/assets
WORKDIR /app
COPY target/ai-watch-spaces-*.jar /app/app.jar
USER aiws
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
