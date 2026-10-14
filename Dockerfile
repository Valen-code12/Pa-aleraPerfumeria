# Usamos una imagen alternativa y muy estable de eclipse-temurin para Java 17
FROM eclipse-temurin:17-jdk

# Crea y define el directorio de trabajo dentro del servidor
WORKDIR /app

# Copia todos los archivos de tu repositorio de GitHub al servidor
COPY . .

# Compila todos los archivos .java que están sueltos en la raíz
RUN javac *.java

# Expone el puerto estándar
EXPOSE 8080

# Comando para ejecutar tu archivo principal que levanta el servidor
CMD ["java", "Servidor"]