# VeriReview deterministic analysis image (Phase 6).
#
# Pinned tool versions are recorded in /opt/versions.properties; backend
# tests assert the pins. Only this image ever touches untrusted project
# code — never the Spring Boot JVM or the host (SECURITY_DESIGN §4).
# Hardening beyond timeouts/caps/no-network arrives in Phase 16.
FROM maven:3.9.9-eclipse-temurin-21

ARG CHECKSTYLE_VERSION=10.12.7
ARG PMD_VERSION=7.5.0
ARG SPOTBUGS_VERSION=4.8.6

RUN apt-get update -qq && apt-get install -y -qq unzip && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/analysis && \
    curl -fsSL -o /opt/analysis/checkstyle-all.jar \
      "https://github.com/checkstyle/checkstyle/releases/download/checkstyle-${CHECKSTYLE_VERSION}/checkstyle-${CHECKSTYLE_VERSION}-all.jar" && \
    curl -fsSL -o /tmp/pmd.zip \
      "https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip" && \
    unzip -q /tmp/pmd.zip -d /opt && \
    mv "/opt/pmd-bin-${PMD_VERSION}" /opt/analysis/pmd && \
    rm /tmp/pmd.zip && \
    curl -fsSL -o /tmp/spotbugs.tgz \
      "https://github.com/spotbugs/spotbugs/releases/download/${SPOTBUGS_VERSION}/spotbugs-${SPOTBUGS_VERSION}.tgz" && \
    tar xzf /tmp/spotbugs.tgz -C /opt/analysis && \
    rm /tmp/spotbugs.tgz && \
    printf 'checkstyle=%s\npmd=%s\nspotbugs=%s\n' \
      "$CHECKSTYLE_VERSION" "$PMD_VERSION" "$SPOTBUGS_VERSION" \
      > /opt/versions.properties

# Pre-warm the offline Maven repo so dependency-free builds compile with -o.
COPY m2-prewarm/pom.xml /tmp/prewarm/pom.xml
COPY m2-prewarm/src /tmp/prewarm/src
RUN cd /tmp/prewarm && mvn -B -q compile && rm -rf /tmp/prewarm

COPY analyze.sh /opt/analyze.sh
RUN chmod +x /opt/analyze.sh

WORKDIR /src
ENTRYPOINT ["/opt/analyze.sh"]
