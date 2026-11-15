# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本。

## [0.3.0] - 2026-11-15

桌面控制台与接口契约的一次统一完善，三仓库同步发布。

### 新增
- `/api/info`：返回 `implementation` / `language` / `version` / `platform` /
  `started_at` / `uptime_seconds` / `endpoints` / `compliance_defaults` /
  `storage_backends` / `result_scan_limit`
- `status` 增加 `implementation` / `platform` / `uptime_seconds`，空闲态补齐全部数值字段
- `/api/results` 支持 `q`（URL/标题子串）、`kind`（精确类型）、`offset`（向历史翻页），
  返回 `{items, total, limit, offset}`；扫描窗口上限 20000 行
- 共用前端 v0.3：12 指标 + 吞吐趋势、结果搜索/筛选/详情/CSV、日志过滤与暂停、
  配置预设、深浅主题、SSE 状态与轮询兜底、键盘快捷键、服务端配置保存
- 三语言自测新增 `/api/info`、结果筛选、状态字段、Content-Type 等断言

### 修复
- Python 一键启动器原按「启动器在项目根目录」的假设查找
  `desktop/launcher.py` 与同目录 `OpenCrawlerLab.exe`，实际交付中启动器在
  `desktop/`、打包程序在 `dist/`，双击会直接弹错误框
- 三个启动器统一为路径自适应 + 额外参数转发（`--no-browser --port N`）+ 无黑框启动；
  实测三语言一键启动均返回 `0.3.0` 与新前端

- Java `asset()` 漏设 `Content-Type` 且缺文件时仍返回 200
- C++ `/api/validate` 校验失败返回 500，与契约的 400 不一致
- 前端结果清空后表格仍显示旧数据；SSE 断线误标运行状态徽标
- 前端按实现裁剪不支持的存储后端选项，避免选到必然校验失败的值

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
