# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
ARG CASHU_GATEWAY_VERSION=0.3.1
COPY . /build
# Optional: install a locally provided Dummy gateway JAR to override the remote artifact at the same version.
# If you place a file at overrides/cashu-gateway-dummy.jar in the repo, it will be used.
RUN if [ -f "/build/overrides/cashu-gateway-dummy.jar" ]; then \
      echo "Installing local cashu-gateway-dummy override (version ${CASHU_GATEWAY_VERSION})" && \
      mvn -q install:install-file \
        -DgroupId=xyz.tcheeric \
        -DartifactId=cashu-gateway-dummy \
        -Dversion=${CASHU_GATEWAY_VERSION} \
        -Dpackaging=jar \
        -Dfile=/build/overrides/cashu-gateway-dummy.jar ; \
    else \
      echo "No local cashu-gateway-dummy override found; using repository version ${CASHU_GATEWAY_VERSION}" ; \
    fi
# Optional: install a locally provided Phoenixd gateway JAR to override the remote artifact at the same version.
# If you place a file at overrides/cashu-gateway-phoenixd.jar in the repo, it will be used.
RUN if [ -f "/build/overrides/cashu-gateway-phoenixd.jar" ]; then \
      echo "Installing local cashu-gateway-phoenixd override (version ${CASHU_GATEWAY_VERSION})" && \
      mvn -q install:install-file \
        -DgroupId=xyz.tcheeric \
        -DartifactId=cashu-gateway-phoenixd \
        -Dversion=${CASHU_GATEWAY_VERSION} \
        -Dpackaging=jar \
        -Dfile=/build/overrides/cashu-gateway-phoenixd.jar ; \
    else \
      echo "No local cashu-gateway-phoenixd override found; using repository version ${CASHU_GATEWAY_VERSION}" ; \
    fi
RUN mvn -B -pl cashu-mint-rest -am -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/cashu-mint-rest/target/cashu-mint-rest-*-exec.jar /app/app.jar
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
EXPOSE 7777
ENTRYPOINT ["java","-jar","/app/app.jar"]
