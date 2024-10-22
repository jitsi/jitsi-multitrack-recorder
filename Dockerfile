ARG JITSI_REPO=jitsi
ARG BASE_TAG=stable
FROM ${JITSI_REPO}/base-java:${BASE_TAG}

LABEL org.opencontainers.image.title="Jitsi Multitrack Recorder"
LABEL org.opencontainers.image.description="An audio recording component."
LABEL org.opencontainers.image.url="https://github.com/jitsi/jitsi-multitrack-recorder"
LABEL org.opencontainers.image.source="https://github.com/jitsi/jitsi-multitrack-recorder"
LABEL org.opencontainers.image.documentation="https://github.com/jitsi/jitsi-multitrack-recorder"


COPY target/jitsi-multitrack-recorder-0.1-SNAPSHOT-jar-with-dependencies.jar /jmr/
COPY rootfs/ /



VOLUME /config

