# Stage only this file, the two named worker sources, and the frozen test-only game.jar.
# Official runtime source: https://github.com/adoptium/containers/tree/main/25/jre/ubuntu/noble
ARG BASE_IMAGE=eclipse-temurin:25-jre-noble
FROM ${BASE_IMAGE}

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 iproute2 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/rwxx-test
COPY game.jar ./game.jar
COPY authoritative_v7_netem_worker.py authoritative_netem_worker.py ./
ARG TEST_JAR_SHA256
RUN test -n "$TEST_JAR_SHA256" \
    && printf '%s  %s\n' "$TEST_JAR_SHA256" /opt/rwxx-test/game.jar | sha256sum --check --strict - \
    && java -version
LABEL io.github.rwx.netem-v7.jar-sha256=$TEST_JAR_SHA256
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /tmp
ENTRYPOINT ["/usr/bin/python3"]
CMD ["-u", "-B", "/opt/rwxx-test/authoritative_v7_netem_worker.py", "--help"]
