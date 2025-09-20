@echo off
:: Minimal Gradle Wrapper script for Windows
:: Uses the wrapper JAR at gradle\wrapper\gradle-wrapper.jar to bootstrap Gradle

setlocal
set DIR=%~dp0
set CLASSPATH=%DIR%\gradle\wrapper\gradle-wrapper.jar;%DIR%\gradle\wrapper\gradle-wrapper-shared.jar

if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
  if exist "%JAVA_EXE%" goto haveJava
  echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
  echo Please set the JAVA_HOME variable in your environment to match the location of your Java installation. 1>&2
  exit /b 1
) else (
  set JAVA_EXE=java
)

:haveJava
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
