@echo off
setlocal

set COMMAND=%1
if "%COMMAND%"=="" set COMMAND=help

if "%COMMAND%"=="help" (
  echo Usage:
  echo   mail-demo.cmd verify
  echo   mail-demo.cmd send
  echo   mail-demo.cmd folders
  echo   mail-demo.cmd receive
  echo   mail-demo.cmd watch
  echo   mail-demo.cmd web
  echo   mail-demo.cmd watch-web
  exit /b 0
)

mvn -q compile exec:java "-Dexec.args=%COMMAND%"
exit /b %ERRORLEVEL%
