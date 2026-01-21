# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY . /build
RUN mvn -B -pl cashu-mint-rest -am -Dmaven.test.skip=true package

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/cashu-mint-rest/target/cashu-mint-rest-*-exec.jar /app/app.jar
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
EXPOSE 7777
ENTRYPOINT ["java","-jar","/app/app.jar"]
