FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

# Compila incluyendo el driver en el classpath
RUN javac -cp ".:lib/*" *.java

EXPOSE 8080

# Ejecuta incluyendo el driver en el classpath
CMD ["java", "-cp", ".:lib/*", "Servidor"]