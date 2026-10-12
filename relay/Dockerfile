# e4hytale relay. Build context = the e4hytale folder (the relay needs :common):
#   docker build -f relay/Dockerfile -t e4hytale-relay .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY common common
COPY relay relay
# mod/ is not copied: settings.gradle.kts includes it, so an empty project stands in (nothing of it is built)
RUN mkdir -p mod && touch mod/build.gradle.kts \
    && chmod +x gradlew \
    && ./gradlew --no-daemon :relay:test :relay:jar

FROM eclipse-temurin:25-jre
RUN useradd --system --home-dir /data --shell /usr/sbin/nologin e4h \
    && mkdir -p /data && chown e4h /data
COPY --from=build /src/relay/build/libs/relay-*.jar /app/relay.jar
USER e4h
ENV E4H_DATA=/data
VOLUME /data
# 5520/udp joiners, 5521/udp host tunnels, 8080/tcp /health and /metrics
EXPOSE 5520/udp 5521/udp 8080/tcp
# `docker exec <container> java -jar /app/relay.jar metrics` prints the counters (the image has no curl or wget)
HEALTHCHECK --interval=60s --timeout=10s --start-period=20s CMD ["java", "-Xmx32m", "-XX:TieredStopAtLevel=1", "-jar", "/app/relay.jar", "health"]
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-Xmx256m", "-jar", "/app/relay.jar"]
