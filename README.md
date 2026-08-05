# AI Gateway & Inference Scheduler

轻量级 AI 网关与智能调度中间件：统一接入多家大模型，基于实时状态做路由与调度（Mini AI Inference Platform）。

## 特性

- 统一 OpenAI 兼容 API，支持非流式与流式（SSE）
- 渠道 / 模型配置驱动，启动时完整校验
- 加权随机路由、健康检查、失败自动切换
- 全链路请求 ID、结构化日志、Prometheus 指标
- 内置 mock 模型服务：可注入延迟、故障、健康状态，方便本地演示
- 路线：插件化流水线、多目标智能调度（延迟 / 成本 / 质量）、容错治理、配额计量

## 技术栈

Java 21 · Spring Boot 3.2 · Spring WebFlux · Maven 多模块

## 快速开始

```bash
# 1) 启动两个 mock 模型实例（8001 / 8002）
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

## 文档

- 概念入门：[docs/plan/00-网关基础概念入门.md](docs/plan/00-网关基础概念入门.md)
- 实现路线：[docs/plan/01-实现总计划.md](docs/plan/01-实现总计划.md)
- 完整设计：[docs/AI Gateway & Inference Scheduler - 完整项目设计文档 v3.0.md](docs/AI%20Gateway%20%26%20Inference%20Scheduler%20-%20完整项目设计文档%20v3.0.md)

## 项目状态

版本 1（统一接入与基础网关）：脚手架已完成，核心算法按学习计划手写中。
