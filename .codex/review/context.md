# 当前任务需求（claude-commit-review 上下文）

## 任务

依据 docs/plan 下的版本规划文档与全局设计文档 v3.0，编写《版本2-详细实施计划》：

- 输出文件：docs/plan/版本2-详细实施计划.md
- 格式对齐《版本1-详细实施计划.md》：手敲清单（H1~H6）、配置设计、领域模型、插件框架、Filter Chain、Scorer、Scheduler、ModelStateStore、DecisionEngine、主流程改造、决策日志、示例插件、指标、时序、测试计划、演示验收、任务清单、风险。
- 手敲核心（H1~H6，约 450 行）与 Codex 脚手架（SPI/Context/Signals/Registry/PolicyManager/过滤器/策略类/决策日志/示例插件）分工明确。

## 验收标准

1. 文档包含完整可执行的关键代码段与接口契约（配置结构、端点、错误体）。
2. 与现有代码（ai-gateway Spring MVC + 虚拟线程、mock-model-server）和全局设计 v3.0 一致。
3. 版本 1 的旧配置（无 policies/plugins）缺省可启动，功能不回退。
4. 演示场景 A/B/C（插件零侵入、可解释路由、fail-closed）与验收标准齐全。

## 涉及文件

- docs/plan/版本2-详细实施计划.md（已提交 fefacaa）
- 参考：docs/plan/版本1-详细实施计划.md、docs/plan/版本2-插件流水线与决策引擎.md、docs/AI Gateway & Inference Scheduler - 完整项目设计文档 v3.0.md

## 约束

- 只做本地 commit，禁止自动 push。
- 文档中文，代码段与现有包结构、命名风格一致。
