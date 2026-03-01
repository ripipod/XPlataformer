@echo off
cd /d "%~dp0"

echo Compilando con .NET...
dotnet build -c Release -o bin\Release\net8.0-windows

if errorlevel 1 (
    echo ERROR en compilacion
    pause
    exit /b 1
)

echo.
echo ^>XPlataformer compilado correctamente!
echo.

REM Ejecutar
start bin\Release\net8.0-windows\XPlataformer.exe
