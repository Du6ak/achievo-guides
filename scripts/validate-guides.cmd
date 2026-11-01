@echo off
setlocal
set "GUIDE_JAVA=java"
if defined JAVA_HOME set "GUIDE_JAVA=%JAVA_HOME%\bin\java.exe"
set "GUIDE_REPO=%~dp0.."
if not "%~1"=="" set "GUIDE_REPO=%~1"
if not exist "%~dp0..\tools\guide-tools.jar" (
    echo Build tools first: gradlew.bat guideTools inside achievo-guides
    exit /b 1
)
"%GUIDE_JAVA%" -jar "%~dp0..\tools\guide-tools.jar" validate "%GUIDE_REPO%"
exit /b %ERRORLEVEL%
