@echo off
rem Start the desktop app on Windows (cmd / PowerShell). Use run-app.sh on bash.
rem
rem   run-app.cmd          build and launch
rem   run-app.cmd --check  compile only, no window
rem
rem Java lookup order: JAVA_HOME, then %%USERPROFILE%%\.jdks\jdk17.
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
  if exist "%USERPROFILE%\.jdks\jdk17\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\jdk17"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

where java >nul 2>nul
if errorlevel 1 (
  echo No JDK found. Set JAVA_HOME, or put JDK 17 under %%USERPROFILE%%\.jdks\jdk17 - see README "Build and test".
  exit /b 1
)

if /i "%~1"=="--check" (
  call gradlew.bat :app:desktop:compileKotlin --console=plain
  exit /b %ERRORLEVEL%
)

call gradlew.bat :app:desktop:run --console=plain
exit /b %ERRORLEVEL%
