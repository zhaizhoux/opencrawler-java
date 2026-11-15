# OpenCrawlerJava · 通用网站爬虫学习框架（Java 21）

> **零第三方依赖**的合规高并发爬虫：只用 JDK 21，用**虚拟线程（Project Loom）**把
> 「每请求一个线程」写成同步代码，既有极高并发，又没有回调地狱。
> 一个 `javac` + `jar` 就能构建，产物是 91 KB 的可执行 jar。

[![CI](https://github.com/zhaizhoux/opencrawler-java/actions/workflows/ci.yml/badge.svg)](https://github.com/zhaizhoux/opencrawler-java/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/java-21%2B-blue)

---

## ⚠️ 先读这一段

这是**教学软件**。默认遵守 `robots.txt`、默认限速、默认限深限页、默认不跟随外链，
**不提供**任何绕过验证码、登录墙、付费墙或反爬机制的能力。
请只抓取公开且你被允许访问的页面，详见 [ETHICS.md](ETHICS.md)。

---

## 为什么需要 Java 版

同一套爬虫语义，三种并发范式，便于对照学习：

| 仓库 | 语言 | 并发模型 | 依赖 | 产物 |
| --- | --- | --- | --- | --- |
| [opencrawler-lab](https://github.com/zhaizhoux/opencrawler-lab) | Python 3.10+ | `asyncio` 事件循环 | aiohttp 等 | pip 包 + PyInstaller exe |
| **opencrawler-java** | **Java 21** | **虚拟线程（每请求一线程，同步写法）** | **仅 JDK** | 单 jar + exe 启动器 |
| [opencrawler-cpp](https://github.com/zhaizhoux/opencrawler-cpp) | C++20 | 固定线程池 + WinHTTP | 系统库 | 原生 exe |

关键取舍：

- **零依赖**：没有 Maven / Gradle / Netty / Jsoup / OkHttp。`javac` 直接编译，
  自带轻量 JSON 解析器、HTML tokenizer 与 CSS 选择器子集。
- **虚拟线程友好**：`HttpClient.sendAsync(...).join()` 在虚拟线程上会正确挂起，
  不会钉住载体线程；`Executors.newVirtualThreadPerTaskExecutor()` + `pool.close()`
  天然实现「等待所有在途请求收尾」的优雅停机。
- **长时运行**：append-only `state.tsv` journal 记录入队与完成事件，重启自动回放未完成项，
  实现断点续爬（比 Python 版 SQLite 更轻，换来的是不做跨进程复杂查询）。
- **同一桌面体验**：`desktop/web/` 与另外两版共用同一套前端与
  [REST + SSE 接口契约](docs/DESKTOP_API.md)。

---

## 快速开始

只需要 **JDK 21+**（无需联网、无需构建工具）：

```bash
# 1. 构建（生成 build/opencrawler-java.jar）
bash build.sh                 # Windows 也可双击 build.bat

# 2. 不访问公网，跑完整自测（25 项断言，应输出 SELFTEST PASS）
java -jar build/opencrawler-java.jar selftest

# 3. 起本机演示站点（robots.txt / HTML / JSON / RSS / Sitemap / 禁止页）
java -jar build/opencrawler-java.jar demo --port 8765
# 另开终端：
java -jar build/opencrawler-java.jar crawl \
    --seed http://127.0.0.1:8765/ --concurrency 8 --delay-ms 0 --max-pages 30 --out ./output

# 4. 调试单个页面
java -jar build/opencrawler-java.jar fetch https://example.com/

# 5. 打开桌面控制台（独立窗口）
java -jar build/opencrawler-java.jar desktop --port 8757
# Windows 双击 desktop/OpenCrawlerJava.exe 即可一键启动（必要时自动先构建）
```

配置文件为 JSON（也兼容极简 `key=value`）：

```bash
java -jar build/opencrawler-java.jar init -o crawler.config.json
java -jar build/opencrawler-java.jar validate --config crawler.config.json
java -jar build/opencrawler-java.jar crawl --config crawler.config.json --dry-run
java -jar build/opencrawler-java.jar crawl --config crawler.config.json
```

命令行覆盖优先于配置文件；`--seed` 可重复，也可用逗号分隔多个 URL。

---

## 核心能力

| 能力 | 实现 |
| --- | --- |
| 通用抽取 | HTML（标题/描述/canonical/h1/正文/OpenGraph）、JSON API、Sitemap、RSS/Atom、纯文本 |
| 定制字段 | 配置化 `rules`：CSS 选择器子集（`tag` / `.class` / `#id` / `[attr]` / 后代）、轻量 JSONPath、正则、`strip`/`int`/`float` 变换 |
| 合规 | robots.txt UA 分组、Allow 优先最长匹配、Crawl-delay、Sitemap 发现、失败默认拒绝、nofollow、敏感路径黑名单、静态资源后缀过滤 |
| 礼貌限速 | 每域信号量 + 请求间隔令牌 + RPS 上限 + 429/Retry-After 惩罚窗口；**睡眠在锁外**，同域并发不被串行化 |
| 高并发 | 虚拟线程池、全局并发上限、AIMD 自适应并发（成功率 + p95 延迟） |
| 长时运行 | 持久优先队列 + `state.tsv` 断点续爬、指数退避重试、条件请求（ETag / Last-Modified）、页数/流量/时间三重预算、优雅停机 |
| 可观测 | 原子指标、周期日志、`metrics.jsonl`、桌面 SSE 实时推送 |
| 桌面 | JDK 内置 `HttpServer` 提供 REST + SSE，Edge/Chrome `--app` 独立窗口 |

不支持（也不打算支持）：JS 渲染、登录态、验证码、代理池轮换、浏览器指纹伪装。
这些能力对学习调度与抽取并无必要，且极易被滥用。

---

## 桌面应用

`desktop` 子命令只监听 `127.0.0.1`，不对外开放；前端与 Python / C++ 版完全相同：
可视化配置、启停、6 个实时指标、日志流、最近结果表格、导出 JSONL。

Windows 用户双击 `desktop/OpenCrawlerJava.exe`：它会先检查 jar，缺失时自动调用
`build.bat`，再用 `javaw` 启动桌面窗口（全程无黑框）。详见 [desktop/README.md](desktop/README.md)。

---

## 测试

```bash
java -jar build/opencrawler-java.jar selftest
```

自测覆盖：JSON 往返、robots 规则与 Crawl-delay、CSS 选择器、JSONPath、AIMD、
限速器并发、持久队列回放、端到端抓取（断言不抓 `/secret`、不抓外链、不抓图片）、
桌面 REST 接口。CI 在 Ubuntu / Windows 的 JDK 21 上运行同一套自测。

---

## 目录结构

```
src/main/java/io/opencrawler/
  Main.java         CLI           Engine.java     虚拟线程调度
  Config.java       配置与校验     Json.java       轻量 JSON
  Fetcher.java      HttpClient    Robots.java     robots.txt
  Limiter.java      每域限速       Frontier.java   持久优先队列
  Extractor.java    HTML/CSS/JSON Storage.java   JSONL 结果
  Metrics.java      指标           Adaptive.java   AIMD
  Desktop.java      REST + SSE    Demo.java       本机演示站
  SelfTest.java     25 项自测     Models.java     不可变数据模型
  Util.java         URL/编码工具
desktop/web/        与 Python / C++ 版共用的前端
docs/               架构与桌面接口契约
```

## 参与贡献 / 安全

见 [CONTRIBUTING 指南（Python 主仓库）](https://github.com/zhaizhoux/opencrawler-lab/blob/main/CONTRIBUTING.md)
与 [SECURITY.md 原则](https://github.com/zhaizhoux/opencrawler-lab/blob/main/SECURITY.md)。
---

## 桌面控制台 v0.3（本次完善）

三语言共用同一套前端（`desktop/web/` 三文件字节级一致），契约见
[docs/DESKTOP_API.md](docs/DESKTOP_API.md)。版本号统一提升到 `0.3.0`。

**页面**：12 个实时指标卡 + 60 秒吞吐趋势折线；结果表支持搜索、类型筛选、
HTTP 状态、耗时、点击展开完整 JSON 详情与 CSV 导出；日志支持级别/关键字过滤、
暂停缓冲、清空、自动滚动开关；配置支持多份本地预设（保存 / 载入 / 删除 /
导入 / 导出）、服务端保存、校验、恢复默认；深色 / 浅色主题持久化；
SSE 连接状态徽标与 3 秒轮询兜底；快捷键 `Ctrl+Enter` 启动、`Ctrl+.` 停止、
`/` 聚焦搜索、`Esc` 关闭详情。

**接口**：新增 `/api/info`（标识 Python / Java / C++ 实现、版本、平台、合规默认值）；
`status` 补齐全部指标与 `implementation` / `uptime_seconds`；
`/api/results` 支持 `q` / `kind` / `offset` 并返回 `total`；
`/api/validate` 三语言统一为 400 + `{valid:false,error}`。

**修复**：Java 静态资源曾漏设 `Content-Type`（中文易乱码）且文件缺失仍返回 200；
C++ `/api/validate` 曾返回 500；前端结果被清空时表格仍显示旧数据、
SSE 断线会误标运行状态徽标。

本项目遵循 [MIT](LICENSE) 许可证。
