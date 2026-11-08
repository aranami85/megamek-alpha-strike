@echo off
rem build.bat - compiles the Alpha Strike mode into megamek-as-classes.jar
rem Usage: build.bat "C:\path\to\MegaMek-0.51.0"     (needs the Java 21 JDK: javac and jar)
setlocal
set "MM=%~1"
if "%MM%"=="" set /p "MM=Your MegaMek 0.51.0 folder (the one with MegaMek.jar): "
if not exist "%MM%\MegaMek.jar" (
  echo MegaMek.jar not found in "%MM%"
  exit /b 1
)
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
dir /s /b src\*.java > build\sources.txt
javac --release 21 -nowarn -encoding UTF-8 -cp "%MM%\MegaMek.jar;%MM%\lib\*" -d build\classes @build\sources.txt
if errorlevel 1 ( echo Compile failed. & exit /b 1 )
rem the text (messages.properties) goes in next to the classes
robocopy src build\classes *.properties /s >nul
jar cf megamek-as-classes.jar -C build\classes .
echo Done: megamek-as-classes.jar
endlocal
