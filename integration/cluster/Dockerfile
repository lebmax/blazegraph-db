# Infrastructure-only runtime. Blazegraph and River remain on the host Axiom JDK.
FROM docker.io/library/eclipse-temurin:8-jre-jammy@sha256:4a7dba2069350de4a7e23dfa52b8bf58c0ed36c23059eaf9c557ff1a0bc2a056

WORKDIR /opt/zookeeper

COPY target/lib/ /opt/zookeeper/lib/
COPY zoo.cfg /opt/zookeeper/conf/zoo.cfg
COPY log4j.properties /opt/zookeeper/conf/log4j.properties
COPY entrypoint.sh /usr/local/bin/zookeeper-entrypoint
COPY healthcheck.sh /usr/local/bin/zookeeper-healthcheck

RUN set -eux; \
    install -d -o 10001 -g 10001 /var/lib/zookeeper; \
    chmod 0555 /usr/local/bin/zookeeper-entrypoint /usr/local/bin/zookeeper-healthcheck; \
    chmod -R a+rX /opt/zookeeper

ENV ZOO_DATA_DIR=/var/lib/zookeeper

EXPOSE 2181 2888 3888

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
    CMD ["/bin/bash", "/usr/local/bin/zookeeper-healthcheck"]

USER 10001:10001

ENTRYPOINT ["/bin/bash", "/usr/local/bin/zookeeper-entrypoint"]