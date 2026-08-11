@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM Provision JDK 27-ea per machine (mirrors build.bat / scripts/ensure-jdk27.sh), then launch.
if exist "deluge-swing.jar" (
    set "JAR=deluge-swing.jar"
) else (
    set "JAR=target\deluge-swing.jar"
)

REM JAVA_HOME must be set alongside JAVA_EXEC, not just JAVA_EXEC: the build step below shells out
REM to mvnw.cmd, which resolves Java via JAVA_HOME (falling back to PATH). Without it, the very
REM path that provisions a JDK here -- no system Java 27, download into .\jdk27, build the jar --
REM would then build with whatever java is on PATH, i.e. the one we just established is missing or
REM wrong. run.sh gets this right by sourcing ensure-jdk27.sh, which exports JAVA_HOME.
if exist jdk27\bin\java.exe (
    set "JAVA_HOME=%CD%\jdk27"
    set "JAVA_EXEC=jdk27\bin\java.exe"
) else (
    REM NOTE: no embedded quote in the search string -- cmd's quote pairing would swallow the
    REM trailing `>nul` into the argument (findstr then reports `Cannot open >nul` and always
    REM fails, so a system JDK 27 would never be detected). `/R` + `.` matches the quote instead.
    java -version 2>&1 | findstr /R /C:"version .27" >nul
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

        set "JAVA_HOME=%CD%\jdk27"
        set "JAVA_EXEC=jdk27\bin\java.exe"
    )
)

REM Build the self-contained Swing fat jar if it's missing OR any source changed since it was built,
REM so edits actually reach the launched app instead of reusing a stale jar (run.sh does the same
REM with `find -newer`; batch has no equivalent, so the timestamp compare is done in PowerShell).
set "NEEDS_BUILD="
if not exist "%JAR%" (
    set "NEEDS_BUILD=1"
) else (
    powershell -NoProfile -Command ^
      "$j=(Get-Item '%JAR%').LastWriteTimeUtc;" ^
      "$newer=Get-ChildItem -Path 'src' -Recurse -Filter '*.java' -ErrorAction SilentlyContinue |" ^
      "  Where-Object { $_.LastWriteTimeUtc -gt $j } | Select-Object -First 1;" ^
      "if ($newer -or (Get-Item 'pom.xml').LastWriteTimeUtc -gt $j) { exit 1 } else { exit 0 }"
    if !errorlevel! equ 1 set "NEEDS_BUILD=1"
)
if defined NEEDS_BUILD (
    echo Building %JAR% ^(missing or sources changed^)...
    REM Full path: cmd only searches the current directory when NoDefaultCurrentDirectoryInExePath
    REM is unset, so a bare `mvnw.cmd` is not reliably found.
    call "%~dp0mvnw.cmd" -q clean package -Pswing-dist -DskipTests
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
