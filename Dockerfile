FROM azul/zulu-openjdk-alpine:11-jre

MAINTAINER Cristian Sfercoci <sfercoci123@proton.me>
LABEL MAINTAINER="Cristian Sfercoci <sfercoci123@proton.me>"


COPY ./build/libs /opt

ENTRYPOINT ["java", "-jar", "/opt/mnimapsync-all.jar"]
