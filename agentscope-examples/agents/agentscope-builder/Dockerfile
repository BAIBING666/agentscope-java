FROM eclipse-temurin:25-jre

WORKDIR /app

ARG JAR_FILE=target/agentscope-builder-1.1.0-SNAPSHOT.jar

COPY ${JAR_FILE} /app/app.jar

ENV SPRING_PROFILES_ACTIVE=jdbc
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/
ENV BUILDER_WORKSPACE=/app/data
ENV JAVA_OPTS=""

RUN mkdir -p /app/config /app/data

EXPOSE 8080

VOLUME ["/app/config", "/app/data"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
