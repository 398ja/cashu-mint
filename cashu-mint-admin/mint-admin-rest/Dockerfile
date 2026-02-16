# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY . /build
RUN mvn -B -pl cashu-mint-admin-rest -am -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/cashu-mint-admin-rest/target/cashu-mint-admin-rest-*.jar /app/app.jar
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
EXPOSE 7778
ENTRYPOINT ["java","-jar","/app/app.jar"]
