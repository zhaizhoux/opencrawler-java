# 架构说明（Java 版）

## 数据流

```
CLI / Desktop
     │  Config (JSON 或 key=value，命令行覆盖)
     ▼
  Engine ──── 主循环：按 adaptive.limit 投递任务
     │          每请求一个虚拟线程（Executors.newVirtualThreadPerTaskExecutor）
     ▼
  Frontier.poll() ── 持久优先队列（state.tsv journal 回放断点）
     ▼
  process(Request)
     ├─ Robots.allowed()          robots.txt 分组 / 最长匹配 / Crawl-delay
     ├─ Limiter.acquire(host)     每域信号量 + 锁外睡眠 + 429 惩罚
     ├─ Fetcher.fetch()           HttpClient.sendAsync().join() / 条件请求 / 退避
     ├─ Extractor.extract()       HTML DOM → CSS 规则 / JSONPath / 链接发现
     ├─ Storage.write()           JSONL + 内容哈希去重
     └─ Frontier.addAll(links) / markDone()
     ▼
  Metrics + Adaptive (AIMD) ── snapshot() ──▶ Desktop SSE / metrics.jsonl
```

## 为什么虚拟线程让代码更简单

传统 Java 高并发爬虫要写 `CompletableFuture` 链或响应式框架；这里每个请求就是一个
普通方法调用，阻塞点（`Thread.sleep`、信号量、`sendAsync().join()`）由 JVM 自动挂起虚拟线程，
载体线程被释放去跑其他请求。于是：

- `process()` 是**自上而下的同步代码**，异常用 try/catch，资源用 try-with-resources；
- 不需要回调、不需要 `thenCompose`、不需要背压框架；
- `pool.close()` 会等所有在途虚拟线程结束，天然实现优雅停机；
- 停机时把已取出但未处理的 `Request` 重新写回持久队列，下次启动自动续爬。

注意用 `sendAsync(...).join()` 而非同步 `send()`：JDK 21 的同步 `send()` 在虚拟线程里
可能钉住载体线程，`sendAsync().join()` 能正确 unmount。

## 各模块职责

| 类 | 职责 | 线程安全手段 |
| --- | --- | --- |
| `Config` | 默认值 + 覆盖 + 校验与合规警告 | 不可变（构造后只读） |
| `Json` | 递归下降解析 / 序列化 | 无状态 |
| `Util` | URL 规范化、编码推断、哈希、路径匹配 | 无状态 |
| `Robots` | robots.txt 缓存与判定 | `ConcurrentHashMap` + 1 小时 TTL |
| `Limiter` | 每域并发/间隔/惩罚 | 每域小对象 `synchronized` 仅覆盖令牌计算，**睡眠在锁外** |
| `Frontier` | 优先队列 + 去重 + journal | `PriorityBlockingQueue` + 并发集合 + `synchronized` append |
| `Fetcher` | HTTP、条件请求、重试 | `HttpClient` 线程安全；ETag 缓存用并发 Map |
| `Extractor` | DOM、选择器、规则、链接 | 每请求独立对象，无共享状态 |
| `Storage` | JSONL 追加与去重 | `synchronized write` + 并发哈希集合 |
| `Metrics` / `Adaptive` | 指标与 AIMD | `AtomicLong` / `synchronized` |
| `Engine` | 调度、预算、停机 | `volatile` 停止标志 + `AtomicInteger` |
| `Desktop` | REST + SSE | 虚拟线程 executor + `synchronized` 日志缓冲 |

## 与 Python 版的差异（教学对照）

| 维度 | Python 版 | Java 版 |
| --- | --- | --- |
| 并发单元 | `asyncio.Task` | 虚拟线程 `Thread.ofVirtual()` |
| HTTP | aiohttp | `java.net.http.HttpClient` |
| 持久队列 | SQLite WAL（表 + 索引） | append-only TSV journal（回放） |
| 插件 | `parsers` / `pipelines` / `middlewares` | 无（保持零依赖） |
| DOM | BeautifulSoup / lxml（XPath） | 自研 tokenizer（CSS 子集） |
| 桌面服务 | aiohttp | `com.sun.net.httpserver` |

两者共享同一套配置字段、合规默认值与桌面接口契约，方便逐行对照学习。
