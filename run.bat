@echo off
setlocal enabledelayedexpansion

set JAR_NAME=deluge-swing.jar

if exist jdk25\bin\java.exe (
    set JAVA_EXEC=jdk25\bin\java.exe
) else (
    java -version 2>&1 | findstr "25." >nul
    if !errorlevel! equ 0 (
        set JAVA_EXEC=java
    ) else (
        echo Java 25 is required but not found.
        echo Attempting to download OpenJDK 25 from Adoptium...
        
        set ARCH=x64
        if "%PROCESSOR_ARCHITECTURE%"=="ARM64" set ARCH=aarch64
        
        set URL=https://api.adoptium.net/v3/binary/latest/25/ga/windows/!ARCH!/jdk/hotspot/normal/eclipse?project=jdk
        
        echo Downloading JDK 25 for Windows (!ARCH!)...
        powershell -Command "[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '!URL!' -OutFile 'openjdk25.zip'"
        
        echo Extracting JDK 25...
        mkdir jdk25
        powershell -Command "Expand-Archive -Path 'openjdk25.zip' -DestinationPath 'jdk25_temp'"
        for /d %%i in (jdk25_temp\*) do (
            move "%%i\*" jdk25\
        )
        rd /s /q jdk25_temp
        del openjdk25.zip
        
        set JAVA_EXEC=jdk25\bin\java.exe
    )
)


echo Launching Deluge...
"!JAVA_EXEC!" --enable-preview --add-modules jdk.incubator.vector -jar "!JAR_NAME!" --swing


pause
