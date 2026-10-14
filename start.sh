
#!/bin/sh
set -e

mkdir -p out

CP="lib/*"

javac -cp "$CP" -d out \
  Administrador.java Cliente.java Persona.java Servidor.java

java -cp "out:lib/*" Servidor
