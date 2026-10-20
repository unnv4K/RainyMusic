@echo off
rem Fallback launcher without a server: opens index.html in an Edge app window.
setlocal
set "P=%~dp0index.html"
set "P=%P:\=/%"
start "" msedge --app="file:///%P%" --window-size=480,880
