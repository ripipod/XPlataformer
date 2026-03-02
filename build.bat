@echo off
setlocal EnableDelayedExpansion

echo.
echo === Build XPlataformer ===
echo.

:: ==================== CONFIGURACIÓN ====================
set "VERSION=1.0.9"
set "JAR_NAME=xplataformer-%VERSION%.jar"
:: Alternativa con fecha automática (descomenta si prefieres):
:: set "FECHA=%date:~6,4%%date:~3,2%%date:~0,2%"
:: set "JAR_NAME=xplataformer-%FECHA%.jar"

set "MAIN_CLASS=com.codename.xplataformer.Main"
set "SRC_DIR=src"
set "BIN_DIR=bin"
set "MANIFEST=manifest.txt"

:: =======================================================

if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

echo [1/4] Compilando...
javac -d "%BIN_DIR%" "%SRC_DIR%\com\codename\xplataformer\Main.java"
if %ERRORLEVEL% neq 0 (
    echo.
    echo ERROR: Fallo al compilar. Revisa los mensajes de javac.
    pause
    exit /b %ERRORLEVEL%
)

echo [2/4] Copiando recursos (icon.png, background.png, etc.)...
xcopy "%SRC_DIR%\*.png" "%BIN_DIR%\" /Y /Q >nul 2>&1
xcopy "%SRC_DIR%\com\codename\xplataformer\*.png" "%BIN_DIR%\com\codename\xplataformer\" /Y /Q >nul 2>&1

echo [3/4] Creando JAR: %JAR_NAME%
jar cvmf "%MANIFEST%" "%JAR_NAME%" -C "%BIN_DIR%" .
if %ERRORLEVEL% neq 0 (
    echo.
    echo ERROR: Fallo al crear el JAR.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo =============================================
echo Listo! Generado: %JAR_NAME%
echo Para ejecutar:
echo java -jar %JAR_NAME%
echo =============================================
echo.

:: Opcional: abrir la carpeta para verlo rápido
:: explorer .

pause