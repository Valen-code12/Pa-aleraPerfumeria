FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY . .
ADD https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar /app/lib/mysql-connector-j.jar
RUN javac -cp ".:lib/*" *.java
EXPOSE 8080
CMD ["java", "-cp", ".:lib/*", "Servidor"]