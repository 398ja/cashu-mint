# Build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY . /build
RUN mvn -B -pl cashu-mint-rest -am -DskipTests package spring-boot:repackage

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/cashu-mint-rest/target/cashu-mint-rest-*.jar /app/app.jar
RUN chown -R /app
EXPOSE 7777
ENTRYPOINT ["java","-jar","/app/app.jar"]
