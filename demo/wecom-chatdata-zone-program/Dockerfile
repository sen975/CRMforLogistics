FROM ubuntu:22.04

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       openjdk-17-jre-headless libssl3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY target/libWeWorkSpecSDK.so /usr/lib/libWeWorkSpecSDK.so
COPY target/wecom-chatdata-zone-program.jar /app/wecom-chatdata-zone-program.jar
COPY start /app/start

RUN chmod 0555 /app/start \
    && chmod 0444 /app/wecom-chatdata-zone-program.jar /usr/lib/libWeWorkSpecSDK.so

EXPOSE 8080
WORKDIR /app
ENTRYPOINT ["/app/start"]
