# Build the React frontend first so the backend can serve the UI and API on one origin.
FROM node:22-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
ENV VITE_API_BASE_URL=/api/v1
RUN npm run build

# Package the frontend output into Spring Boot's static resources.
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /workspace
COPY . .
RUN mkdir -p src/main/resources/static
COPY --from=frontend-build /workspace/frontend/dist/ ./src/main/resources/static/
RUN chmod +x ./mvnw && ./mvnw -B clean package -DskipTests

# Small, non-root runtime image.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 aiws \
    && mkdir -p /var/lib/aiws/assets \
    && chown -R aiws:aiws /var/lib/aiws
WORKDIR /app
COPY --from=backend-build --chown=aiws:aiws /workspace/target/ai-watch-spaces-*.jar /app/app.jar
USER aiws
ENV AIWS_ASSETS_DIRECTORY=/var/lib/aiws/assets
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
