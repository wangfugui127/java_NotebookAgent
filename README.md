# NotebookAgent Java

Java 21 / Spring Boot 3.5 后端，保留原 Vue 前端。包含登录、Notebook 资料摄入、混合检索、Agent 工具调用、引用、SSE、审批恢复、附件全文阅读、图谱、MCP、Skills 与规则评测。业务实现及任务执行均在 Java 后端；镜像内 Python/uv 仅用于启动配置中的第三方 Semantic Scholar MCP 子进程。

项目位置：`F:\Project Practice\java项目\java_notebook`。原 Python 项目保留在 `F:\Project Practice\notebook\notebookagent`。

## 启动

需要 Docker Desktop（Linux 容器）。已有 `.env` 保留；首次复制到其他机器时从 `.env.example` 创建 `.env`，填写 `DEEPSEEK_API_KEY`、`SILICONFLOW_API_KEY` 与随机 `APP_SECRET_KEY`。不要提交密钥文件。

```powershell
Set-Location 'F:\Project Practice\java项目\java_notebook'
docker compose --profile core --profile graph up -d --build --wait --wait-timeout 240
```

- 页面：http://127.0.0.1:13000
- 后端健康：http://127.0.0.1:18001/health（实际检查 MySQL/Redis）
- MCP Streamable HTTP：http://127.0.0.1:13000/mcp
- Neo4j Browser：http://127.0.0.1:17474

浏览器访问支持 `http://127.0.0.1:13000` 和 `http://localhost:13000`，两者均在后端 CORS 允许列表中。新增页面域名或端口时，需要同步 `WebConfig.addCorsMappings` 并重建后端镜像。

Worker 独立运行，内部 HTTP 端口只用于健康检查，没有发布宿主机端口。Compose 项目名为 `java-notebookagent`，数据卷与原项目分开。宿主机数据库端口为 MySQL `13317`、Redis `16381`、Milvus `19531`、Neo4j Bolt `17687`。

```powershell
docker compose --profile core --profile graph logs -f api worker
docker compose --profile core --profile graph down
```

正常停机不要添加 `-v`，数据保存在 Docker 命名卷中。再次启动时可用 `up -d --no-build --wait`。

## 数据与执行语义

MySQL 是业务事实源，Milvus/Neo4j 保存派生索引。Milvus 写入失败会使资料摄入失败，图谱失败只影响 `graph_status`。删除校验资料归属并清理数据库，外部索引清理失败不阻塞数据库删除。

后台任务使用 MySQL `task_execution` outbox 与 Redis Streams。数据库原子领取、租约、心跳和执行身份保护状态写入；过期任务、失联消费者及丢失的队列消息可恢复。投递语义是至少一次，不承诺外部操作恰好一次。

审批保存整个工具批次及执行位置，拒绝不会调用写工具，批准后由 Worker 恢复。引用由保存的 evidence 校验。SSE 每个连接独立订阅，持久事件可按序号从数据库重放。附件按实际输入预算拆分窗口；超预算不能靠静默丢正文解决。

## 验证

```powershell
Set-Location 'F:\Project Practice\java项目\java_notebook\backend'
mvn -q -DskipTests compile
mvn -q test
Set-Location '..\frontend'
npm run build
npm run lint
```

后端测试需要 Docker，Testcontainers 自动创建独立 MySQL/Redis 测试容器，不使用项目现有数据。`docker-java.properties` 固定兼容本机 Docker 的 API 版本。当前共 55 项测试，包含 18 项真实数据库/队列及 HTTP 集成测试；外部模型的异常场景由本地 HTTP fixture 验证。

真实服务验收脚本（会调用模型并产生少量费用，只创建和删除自己的测试资料）：

```powershell
Set-Location 'F:\Project Practice\java项目\java_notebook'
python verification/docker_acceptance.py
python verification/runtime_acceptance.py
python verification/verify_protected.py
```

验收 Python 脚本需要 `requests`；它们是测试客户端。结果与能力边界见 [验收报告](verification/验收报告-2026-10-10.md)，机器可读结果在 `verification/*-results.json`。测试账号凭据与保护基线仅保存在忽略的本地目录，不提交。

真实验收只证明本机样本流程；不代表生产容量、任意崩溃点无重复副作用，或外部论文 API 永远可用。Semantic Scholar 未配置 API Key 时仍可发现工具，实际查询受第三方限流影响。

## 目录

- `backend/`：Java 后端、Flyway 迁移和测试。
- `frontend/`：保留的 Vue 前端。
- `config/`、`skills/`：MCP、评测和 literature-review 配置。
- `verification/`：回归测试日志、真实部署验收脚本和报告。
- `session-ses_*.md`：保留的 OpenCode 历史记录，不代表最终验收状态。


2026-10-11 对照 Python 修复：PDF 章节/分块页码按实际字符区间计算；Eval 统一去空白及重复来源计分，未配置有效维度返回 error；文档路由失败降级分块检索；SSE 支持 Last-Event-ID 优先于 after；附件窗口结论分层合并并保留来源范围与证据标记；工具目录使用 embedding 排序、离线降级，并保存候选集用于审批恢复。详见 [本次修复验收](verification/边界修复验收-2026-10-11.md)。

Docker 的外部 MCP 使用构建时缓存（UV_OFFLINE=1），防止冷启动下载依赖触发协议超时；新增 MCP 依赖后需重新构建镜像。
