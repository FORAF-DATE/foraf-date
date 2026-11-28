FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
COPY services/ms-auth/pom.xml services/ms-auth/pom.xml
COPY services/ms-auth/mvnw services/ms-auth/mvnw
COPY services/ms-auth/.mvn services/ms-auth/.mvn
COPY services/ms-auth/src services/ms-auth/src
COPY .env .env
RUN chmod +x services/ms-auth/mvnw && \
    cd services/ms-auth && ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/ms-auth/target/ms-auth-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
