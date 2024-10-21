ARG JITSI_REPO=jitsi
ARG BASE_TAG=stable
FROM ${JITSI_REPO}/base-java:${BASE_TAG}

LABEL org.opencontainers.image.title="Jitsi Multitrack Recorder"
LABEL org.opencontainers.image.description="An audio recording component."
LABEL org.opencontainers.image.url="https://github.com/jitsi/jitsi-multitrack-recorder"
LABEL org.opencontainers.image.source="https://github.com/jitsi/jitsi-multitrack-recorder"
LABEL org.opencontainers.image.documentation="https://github.com/jitsi/jitsi-multitrack-recorder"

#RUN apt-dpkg-wrap apt-get update && \
#    apt-dpkg-wrap apt-get install -y maven git && \
#    apt-cleanup

#COPY src pom.xml /build/
#RUN git clone "https://github.com/bgrozev/jicoco" && cd jicoco && git checkout recording
#RUN cd jicoco && mvn install -DskipTests -Dktlint.skip
#RUN cd build && mvn install -DskipTests -Dktlint.skip

#RUN cp build/target/jitsi-multitrack-recorder-0.1-SNAPSHOT-jar-with-dependencies.jar /
COPY target/jitsi-multitrack-recorder-0.1-SNAPSHOT-jar-with-dependencies.jar /jmr/
COPY logging.properties /jmr/
COPY jmr.conf /jmr/

COPY rootfs/ /



VOLUME /config

