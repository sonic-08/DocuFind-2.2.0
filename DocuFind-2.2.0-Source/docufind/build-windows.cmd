@echo off
setlocal
cd /d "%~dp0"

where /q mvn.cmd || (echo Install Maven 3.9 or newer and add it to PATH. & exit /b 1)
where /q jpackage.exe || (echo Install a Windows x64 JDK 21 and add its bin directory to PATH. & exit /b 1)
where /q jlink.exe || (echo JDK 21 jlink.exe is missing from PATH. & exit /b 1)
where /q candle.exe || (echo Install WiX Toolset 3.14 and add its bin directory to PATH. & exit /b 1)
where /q light.exe || (echo WiX light.exe is missing from PATH. & exit /b 1)

call mvn.cmd -B clean verify
if errorlevel 1 exit /b 1

set "APP_VERSION=2.2.0"
set "BUILD_DIR=target\windows"
mkdir "%BUILD_DIR%\input" || exit /b 1
copy /y "target\docufind.jar" "%BUILD_DIR%\input\docufind.jar" >nul || exit /b 1

jlink --add-modules java.desktop,java.sql,jdk.unsupported --strip-debug ^
  --no-header-files --no-man-pages --compress=2 --output "%BUILD_DIR%\runtime"
if errorlevel 1 exit /b 1

jpackage --type app-image --name DocuFind --app-version "%APP_VERSION%" ^
  --input "%BUILD_DIR%\input" --main-jar docufind.jar --main-class io.docufind.DocuFind ^
  --runtime-image "%BUILD_DIR%\runtime" --icon "packaging\DocuFind.ico" --dest "%BUILD_DIR%\image"
if errorlevel 1 exit /b 1

jpackage --type exe --name DocuFind --app-version "%APP_VERSION%" --vendor "Team DocuFind" ^
  --app-image "%BUILD_DIR%\image\DocuFind" --dest "%BUILD_DIR%\installer" ^
  --win-per-user-install --win-menu --win-shortcut ^
  --win-upgrade-uuid "63e4a784-f2c7-4365-815a-caa3be4aebac"
if errorlevel 1 exit /b 1

if not exist "%BUILD_DIR%\installer\DocuFind-%APP_VERSION%.exe" (
  echo jpackage finished, but the expected Windows installer is missing.
  exit /b 1
)
echo Built %BUILD_DIR%\installer\DocuFind-%APP_VERSION%.exe
