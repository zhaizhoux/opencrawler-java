#!/usr/bin/env bash
# 零依赖构建：只需要 JDK 21+，不需要 Maven / Gradle / 网络。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac --release 21 -encoding UTF-8 -Xlint:deprecation -d build/classes @build/sources.txt
jar --create --file build/opencrawler-java.jar \
    --main-class io.opencrawler.Main \
    -C build/classes . -C desktop web
echo "构建完成: build/opencrawler-java.jar"
echo "运行: java -jar build/opencrawler-java.jar selftest"
