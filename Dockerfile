# Usa una imagen oficial de Java para compilar y correr la aplicación
FROM openjdk:17-jdk-slim

# Crea y define el directorio de trabajo dentro del servidor
WORKDIR /app

# Copia todos los archivos de tu repositorio de GitHub al servidor
COPY . .

# Compila todos los archivos .java que están sueltos en la raíz
RUN javac *.java

# Expone el puerto estándar que Railway suele asignar (o el que use tu Servidor.java)
EXPOSE 8080

# Comando para ejecutar tu archivo principal que levanta el servidor
CMD ["java", "Servidor"]
