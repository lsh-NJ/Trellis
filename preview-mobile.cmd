@echo off
rem 在电脑上预览手机界面（390x844 的窗口），不用装 APK。
rem
rem   preview-mobile.cmd              开窗，可以真的点
rem   preview-mobile.cmd --seed       先灌一份演示数据再开窗
rem   preview-mobile.cmd --shots      不开窗，把八个页面渲染成 PNG（手机 + 平板）
rem
rem 手机界面在电脑上跑得起来，是因为界面代码在 :app:ui 的 jvmAndAndroidMain 里，
rem 那份源码同时编译给 jvm 和 android —— 不是另写了一套网页版。
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
  if exist "%USERPROFILE%\.jdks\jdk17\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\jdk17"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

set "ARGS="
if /i "%~1"=="--seed" set "TRELLIS_SEED=1"
if /i "%~1"=="--shots" set "TRELLIS_SHOT_DIR=%CD%\build\preview"

call gradlew.bat :app:mobile-preview:run --console=plain
endlocal
