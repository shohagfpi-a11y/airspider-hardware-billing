# ---- build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN ./mvnw -B -q -DskipTests package

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
# fonts are embedded into PDFs; curl is used by the health check
RUN apt-get update \
 && apt-get install -y --no-install-recommends fonts-dejavu-core curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 1001 --home /app app \
 && mkdir -p /app /data/uploads \
 && chown -R app:app /app /data
COPY --from=build /src/target/hardware-billing-*.jar /app/app.jar
USER app
WORKDIR /app
ENV UPLOAD_DIR=/data/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
