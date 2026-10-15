FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

# Descarga el conector de MySQL
ADD https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar /app/lib/mysql-connector-j.jar

# Compila incluyendo el driver en el classpath
RUN javac -cp ".:lib/*" *.java

EXPOSE 8080

# Ejecuta incluyendo el driver en el classpath
CMD ["java", "-cp", ".:lib/*", "Servidor"]