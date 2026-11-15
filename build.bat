@echo off
rem 零依赖构建：只需要 JDK 21+，不需要 Maven / Gradle / 网络。
setlocal
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
dir /s /b src\main\java\*.java > build\sources.txt
javac --release 21 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 goto :fail
jar --create --file build\opencrawler-java.jar --main-class io.opencrawler.Main -C build\classes . -C desktop web
if errorlevel 1 goto :fail
echo 构建完成: build\opencrawler-java.jar
echo 运行: java -jar build\opencrawler-java.jar selftest
exit /b 0
:fail
echo 构建失败，请确认已安装 JDK 21+ 并加入 PATH。
exit /b 1
