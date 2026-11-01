@echo off
setlocal
set "GUIDE_JAVA=java"
if defined JAVA_HOME set "GUIDE_JAVA=%JAVA_HOME%\bin\java.exe"
set "GUIDE_REPO=%~dp0.."
if not "%~1"=="" set "GUIDE_REPO=%~1"
set "GUIDE_OUTPUT=%GUIDE_REPO%\public"
if not "%~2"=="" set "GUIDE_OUTPUT=%~2"
set "GUIDE_TIMESTAMP=1970-01-01T00:00:00Z"
if not "%~3"=="" set "GUIDE_TIMESTAMP=%~3"
if not exist "%~dp0..\tools\guide-tools.jar" (
    echo Build tools first: gradlew.bat guideTools inside achievo-guides
    exit /b 1
)
"%GUIDE_JAVA%" -jar "%~dp0..\tools\guide-tools.jar" build "%GUIDE_REPO%" "%GUIDE_OUTPUT%" "--timestamp=%GUIDE_TIMESTAMP%"
exit /b %ERRORLEVEL%
