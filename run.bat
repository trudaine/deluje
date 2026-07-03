@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Provision JDK 27-ea per machine (mirrors build.bat / scripts/ensure-jdk27.sh), then launch.
set "JAR=target\deluge-swing.jar"

if exist jdk27\bin\java.exe (
    set "JAVA_EXEC=jdk27\bin\java.exe"
) else (
    java -version 2>&1 | findstr /C:"version \"27" >nul
    if !errorlevel! equ 0 (
        set "JAVA_EXEC=java"
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

        set "JAVA_EXEC=jdk27\bin\java.exe"
    )
)

REM Build the self-contained Swing fat jar if it isn't present yet (via the mvnw.cmd wrapper).
if not exist "%JAR%" (
    echo %JAR% not found -- building it ^(first run^)...
    call mvnw.cmd -q clean package -Pswing-dist -DskipTests
)

echo Launching Deluge (%JAR%)...
REM HiDPI text: the AA flags make Swing text high-def. Windows auto-detects the OS display-scaling
REM setting (e.g. 150%%) per-monitor since Java 9, so we do NOT force sun.java2d.uiScale here -- that
REM would override the user's chosen Windows scale. Set DELUGE_UI_SCALE to override if you want a
REM fixed scale (e.g. set DELUGE_UI_SCALE=2 before running).
set "SCALE_FLAGS="
if defined DELUGE_UI_SCALE set "SCALE_FLAGS=-Dsun.java2d.uiScale=%DELUGE_UI_SCALE% -Dsun.java2d.uiScale.enabled=true"
REM --enable-preview is REQUIRED: classes are compiled with preview features and won't load without it.
"!JAVA_EXEC!" --enable-preview --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector %SCALE_FLAGS% -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true -jar "%JAR%" --swing


pause
