# AI Gateway & Inference Scheduler

轻量级 AI 网关与智能调度中间件：统一接入多家大模型，基于实时状态做路由与调度（Mini AI Inference Platform）。

## 特性

- 统一 OpenAI 兼容 API，支持非流式与流式（SSE）
- 渠道 / 模型配置驱动，启动时完整校验
- 插件框架：GLOBAL / ROUTE / MODEL 作用域，按 order 顺序执行，共享 PluginContext，**fail-closed**（候选被清空直接拒绝）
- 决策引擎：Filter Chain（能力 / 可用性 / 策略 / 预算 / 插件信号五道关卡）→ Scorer（延迟 / 成本 / 质量 / 健康多目标打分，支持动态调权）→ Scheduler（出方案，主选 + 降级链 + 打分明细）
- 三类可配置策略：`MULTI_OBJECTIVE` 多目标加权、`WEIGHTED_RANDOM` 加权随机、`CONDITIONAL` 条件路由（按信号求值，未命中回退）
- 状态闭环：EWMA 延迟 / 错误率实时修正，驱动下一次决策（可解释路由）
- 加权随机路由、健康检查、失败自动切换（决策层出方案，执行层沿降级链再试一次）
- 全链路请求 ID、结构化日志、决策环形缓冲（`/v1/debug/decisions` 可查最近 N 条）、Prometheus 指标
- 内置 mock 模型服务：可注入延迟、故障、健康状态，方便本地演示
- 路线：容错治理（重试退避 / 熔断）、配额计量与预算核算、管理 API

## 技术栈

Java 21 · Spring Boot 3.2 · Spring MVC + 虚拟线程（阻塞式）· Maven 多模块

## 快速开始

```bash
# 1) 启动两个 mock 模型实例（8001 / 8002，延迟拉开：qwen-large 约 2000ms、qwen-small 约 200ms）
cd mock-model-server
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8001,--mock.model=qwen-large
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8002,--mock.model=qwen-small

# 2) 启动网关（8080）
cd ../ai-gateway
mvn spring-boot:run

# 3) 调用
curl -s http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen","messages":[{"role":"user","content":"你好"}]}'
```

### V2 演示

```bash
# 演示 A-1：插件零侵入生效——复杂任务命中条件路由 → 只选 qwen-large
curl -s http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen","messages":[{"role":"user","content":"请写一段 Java 代码实现快速排序"}]}'
# 网关日志：event=decision strategy=simple-task ruleHit=rule#0:task_complexity=COMPLEX primary=mock-a:qwen-large

# 演示 A-2：金丝雀分组——experimental 组只进灰度候选（qwen-small）
curl -s http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H 'X-Canary-Group: experimental' \
  -d '{"model":"qwen","messages":[{"role":"user","content":"你好"}]}'
# → primary=mock-b:qwen-small

# 演示 B：可解释路由——最近决策的打分明细（raw / normalized / weights / finalScore）
curl -s 'http://localhost:8080/v1/debug/decisions?limit=5'

# 演示 C：fail-closed——在 model.yml 的 plugins 段把 clear-candidates 的 enabled 改为 true 后重启，
# 所有请求返回 503 {"type":"candidates_cleared", ...}
```

## 文档

- 概念入门：[docs/plan/00-网关基础概念入门.md](docs/plan/00-网关基础概念入门.md)
- 实现路线：[docs/plan/01-实现总计划.md](docs/plan/01-实现总计划.md)
- 完整设计：[docs/AI Gateway & Inference Scheduler - 完整项目设计文档 v3.0.md](docs/AI%20Gateway%20%26%20Inference%20Scheduler%20-%20完整项目设计文档%20v3.0.md)
- 版本 1 实施计划：[docs/implementation/版本1-详细实施计划.md](docs/implementation/版本1-详细实施计划.md)
- 版本 2 实施计划：[docs/implementation/版本2-详细实施计划.md](docs/implementation/版本2-详细实施计划.md)
- 版本 3 实施计划：[docs/implementation/版本3-详细实施计划.md](docs/implementation/版本3-详细实施计划.md)

## 项目状态

版本 1（统一接入与基础网关）：已实现非流式/流式转发、加权随机路由、健康过滤与失败切换、请求 ID、指标、mock 故障注入；配套单元测试与集成测试。

版本 2（插件框架与决策引擎）：已实现插件流水线（含 fail-closed）、Filter Chain 五道关卡、多目标打分（动态调权 + 打分明细）、三类可配置策略、EWMA 状态闭环、决策日志环形缓冲与调试端点、三个示例插件（复杂度识别 / 金丝雀分组 / 清空候选测试）；92 个单元测试与集成测试全绿（含 V1 回归四场景与 V2 场景 A/B/C）。
