# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml ./
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy AS jre
FROM python:3.12-slim
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH \
    UV_CACHE_DIR=/app/mcp-cache UV_PYTHON=/usr/local/bin/python3 UV_PYTHON_DOWNLOADS=never
# Python is used only by external MCP subprocesses; all business services run on Java.
RUN pip install --no-cache-dir uv==0.8.22 \
    && uvx --from semantic-scholar-mcp==0.4.0 --with 'mcp<2' python --version \
    && groupadd --system app && useradd --system --create-home --gid app app \
    && mkdir -p /data/uploads && chown -R app:app /data/uploads /app/mcp-cache /home/app
# Runtime MCP uses the dependencies prepared above; refreshing packages during
# initialization can exceed the protocol timeout and leave an empty tool catalog.
ENV UV_OFFLINE=1
WORKDIR /app/backend
COPY --chown=app:app --from=build /build/target/notebookagent.jar /app/backend/app.jar
USER app
EXPOSE 8001
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=12 CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/health', timeout=4)" || exit 1
ENTRYPOINT ["java", "-jar", "/app/backend/app.jar"]
