@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Self-contained build: provisions JDK 27-ea (per machine) + Maven (via the committed mvnw.cmd
REM wrapper), then runs Maven. No global installs needed.
REM   build.bat                 -> clean package
REM   build.bat test            -> passes goals/args through to mvnw.cmd

if exist jdk27\bin\java.exe (
    set "JAVA_HOME=%CD%\jdk27"
) else (
    java -version 2>&1 | findstr /C:"version \"27" >nul
    if !errorlevel! equ 0 (
        echo Using system Java 27.
    ) else (
        echo Java 27 is required but not found.
        echo Downloading OpenJDK 27 ^(early-access^) from Adoptium...

        set ARCH=x64
        if /I "%PROCESSOR_ARCHITECTURE%"=="ARM64" set ARCH=aarch64

        set "URL=https://api.adoptium.net/v3/binary/latest/27/ea/windows/!ARCH!/jdk/hotspot/normal/eclipse?project=jdk"
        echo Downloading JDK 27 for Windows ^(!ARCH!^)...
        powershell -Command "[System.Net.ServicePointManager]::SecurityProtocol=[System.Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '!URL!' -OutFile 'openjdk27.zip'"

        echo Extracting JDK 27...
        if exist jdk27_temp rd /s /q jdk27_temp
        mkdir jdk27_temp
        powershell -Command "Expand-Archive -Path 'openjdk27.zip' -DestinationPath 'jdk27_temp' -Force"
        for /d %%i in (jdk27_temp\*) do move "%%i" jdk27 >nul
        rd /s /q jdk27_temp
        del openjdk27.zip

        set "JAVA_HOME=%CD%\jdk27"
    )
)

set "GOALS=%*"
if "%GOALS%"=="" set "GOALS=clean package"

echo Building with mvnw.cmd (%GOALS%)...
call mvnw.cmd %GOALS%
