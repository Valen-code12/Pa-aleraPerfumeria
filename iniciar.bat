@echo off
rem Inicia el servidor de La Placita sin abrir Visual Studio Code.
rem Guardar este archivo en la carpeta del proyecto (al lado de Servidor.java y config.properties).
cd /d "%~dp0"

echo Compilando...
if not exist clases mkdir clases
javac -encoding UTF-8 -cp "lib/*" -d clases *.java
if errorlevel 1 (
  echo.
  echo No se pudo compilar. Revisa los errores de arriba.
  pause
  exit /b 1
)

echo.
echo Iniciando el servidor. NO cierres esta ventana mientras uses la pagina.
rem Abre el navegador a los 3 segundos, cuando el servidor ya arranco
start "" cmd /c "timeout /t 3 >nul & start http://localhost:8080/InicioDeSesion.html"
java -Dfile.encoding=UTF-8 -cp "clases;lib/*" Servidor
pause
