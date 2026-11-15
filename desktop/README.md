# 桌面应用与一键启动

Java 版桌面层用 **JDK 内置 `com.sun.net.httpserver.HttpServer`** 提供 REST + SSE，
再调用 Edge / Chrome 的 `--app` 模式打开无地址栏的独立窗口。
前端 `web/` 与 Python、C++ 版完全相同，接口契约见 [docs/DESKTOP_API.md](../docs/DESKTOP_API.md)。

## 启动方式

| 方式 | 命令 | 说明 |
| --- | --- | --- |
| jar | `java -jar build/opencrawler-java.jar desktop` | 跨平台 |
| 批处理 | 双击 `run.bat` | 自动构建 + `javaw` 无黑框启动 |
| **exe** | 双击 `desktop/OpenCrawlerJava.exe` | 检查 jar → 缺失则自动 `build.bat` → 启动桌面 |
| exe 带参数 | `OpenCrawlerJava.exe --no-browser --port 9000` | 参数原样转给主程序，便于调试 |

`OpenCrawlerJava.exe` 是 45 KB 的 Win32 GUI 启动器（源码 `WinLauncher.cpp`），
只做「找 jar / 构建 / 启动」，不含爬虫逻辑，升级 Java 代码无需重新编译。
启动器放在项目根目录、`desktop/` 或 `build/` 下都能自动定位 jar，且无黑框。
重新编译：

```bash
g++ -O2 -s -mwindows desktop/WinLauncher.cpp -o desktop/OpenCrawlerJava.exe
```

## 端口与安全

默认 `8757`，可用 `--port` 修改；服务**只绑定 127.0.0.1**，静态资源走白名单，
结果文件固定为 `output_dir/items.jsonl`，拒绝任意路径读取与路径穿越。

桌面界面不会降低合规默认值：robots.txt、Crawl-delay、内容去重与自适应并发默认开启，
`respect_robots` 开关仅供自有站点教学实验。

## 前端契约

`web/index.html` / `app.css` / `app.js` 不依赖具体语言，只要后端实现
`GET /api/status`、`POST /api/start`、`POST /api/stop`、`GET /api/results`、
`GET /api/events`（SSE）等接口即可。三版共用同一份文件，行为一致。

## v0.3 前端能力

`web/` 三文件与另外两版字节级一致。除原有配置、启停、指标、日志、结果表格外，新增：
吞吐趋势折线、结果搜索/类型筛选/JSON 详情/CSV 导出、日志级别过滤与暂停缓冲、
多份配置预设（localStorage 持久化，支持 JSON 导入导出）、深色/浅色主题、
SSE 连接状态徽标与轮询兜底、键盘快捷键。

前端会先请求 `/api/info` 判断连的是 Python / Java / C++ 哪一版并显示实现徽标；
旧版服务端没有该接口时自动降级，不会报错。
