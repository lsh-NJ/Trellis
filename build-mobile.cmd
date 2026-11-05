@echo off
rem Build the Android APK on Windows (cmd / PowerShell).
rem
rem   build-mobile.cmd            assembleDebug, then print the APK path
rem   build-mobile.cmd install    also install to the connected phone via adb
rem
rem Java lookup order: JAVA_HOME, then %USERPROFILE%\.jdks\jdk17.
rem Android SDK is read from local.properties (sdk.dir) by Gradle itself.
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
  echo JAVA_HOME is not set. Point it at a JDK 17, for example:
  echo   setx JAVA_HOME "E:\workspace2\jdk-17.0.2"
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

where java >nul 2>nul
if errorlevel 1 (
  echo No JDK found. Set JAVA_HOME, or put JDK 17 under %%USERPROFILE%%\.jdks\jdk17.
  exit /b 1
)

call gradlew.bat :app:mobile:assembleDebug --console=plain
if errorlevel 1 exit /b %ERRORLEVEL%

set "APK=app\mobile\build\outputs\apk\debug\mobile-debug.apk"
echo.
echo APK: %CD%\%APK%
echo 把它拷到手机上安装，或者用数据线连上手机后执行：build-mobile.cmd install

if /i "%~1"=="install" (
  set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
  if not exist "%ADB%" (
    echo adb not found at %ADB% - open the path above and install manually.
    exit /b 1
  )
  "%ADB%" install -r "%APK%"
)

endlocal
