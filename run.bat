@echo off
rem 一键启动桌面控制台（无控制台窗口）。首次运行会自动构建。
setlocal
cd /d "%~dp0"
if not exist build\opencrawler-java.jar call build.bat
if errorlevel 1 exit /b 1
start "" javaw -jar build\opencrawler-java.jar desktop
