# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [0.2.0] - 2026-11-15

首个公开版本，与 Python 主仓库 0.2.0 的功能族对齐。

### 新增
- JDK 21 虚拟线程调度引擎：每请求一个虚拟线程、同步写法、`pool.close()` 优雅停机
- 零第三方依赖：`Json` 解析器、HTML tokenizer、CSS 选择器子集、轻量 JSONPath 全部自研
- 合规层：robots.txt 分组/最长匹配/Crawl-delay/Sitemap、失败默认拒绝、nofollow、黑名单
- 每域限速（锁外睡眠）、429 惩罚窗口、AIMD 自适应并发
- `state.tsv` append-only journal 实现断点续爬；ETag / Last-Modified 条件请求
- HTML / JSON / Sitemap / RSS 自适应抽取 + 配置化规则
- CLI：`crawl` / `fetch` / `demo` / `selftest` / `desktop` / `validate` / `init` / `version`
- 桌面控制台：JDK 内置 `HttpServer` 实现 REST + SSE，复用 Python / C++ 版前端
- `build.sh` / `build.bat` 一键构建可执行 jar；Windows exe 一键启动器
- 内置 20 项自测（`SELFTEST PASS`），含端到端抓取合规断言

### 已知取舍
- 持久队列用 TSV journal，不做跨进程复杂查询（Python 版用 SQLite WAL）
- 不实现 JS 渲染、XPath、插件系统（Java 版定位为零依赖教学实现）
