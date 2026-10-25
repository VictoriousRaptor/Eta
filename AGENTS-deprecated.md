# Eta 项目 Agent 规则

> 已弃用的历史规则归档，仅供参考，不作为当前 Agent 的执行指令。

## 角色划分

### Codex（计划者 + 验收者）
- 理解需求，制定具体实施计划并界定文件范围
- 在需要实施文件修改的任务中，**必须先读取项目 agy-worker skill**（[.agents/skills/agy-worker/SKILL.md](.agents/skills/agy-worker/SKILL.md)）了解完整调用流程（纯问答或只读诊断不强制触发）
- 通过 `scripts/agy.ps1`（兼容入口）调用 agy-worker 执行改码
- 运行编译、测试等 shell 命令验证结果
- 审查 agy 产生的 diff，检测越界修改
- 调用失败时使用同一会话 ID 续修，不静默换弱模型

### agy-worker（实施者）
- 接收 Codex 的计划，在指定文件范围内实现代码
- 不运行 shell 编译/测试命令
- 不自主扩展修改范围
- 遵循计划中的约束和风格要求

## agy 调用规则

实施任务的完整调用流程、模型策略与失败判定以项目 agy-worker skill 为唯一来源：

- **技能定义**：[.agents/skills/agy-worker/SKILL.md](.agents/skills/agy-worker/SKILL.md)
- **命令例外**：[docs/agy/COMMAND_EXCEPTION.md](docs/agy/COMMAND_EXCEPTION.md)

### 核心协作指引（摘要）
- **调用入口与命令例外**：通过规范路径 `.agents/skills/agy-worker/scripts/agy.ps1` 或兼容入口 `scripts/agy.ps1` 调用；Codex exec_command 场景严格使用命令例外中的固定命令，参数准备与调用分开提交。用户已授权本项目必要内容传输与会话操作（`--dangerously-skip-permissions`）。
- **同会话续修**：调用失败或需修正时，读取 `.agy-runs/` 中记录的原会话 ID 和模型进行续修，不静默更换弱模型。
- **审查与独立验收**：AllowedFiles 为事后校验而非硬沙箱。Codex 审查 `.agy-runs/` 留存证据与 diff，独立运行编译/测试验证后才算验收通过。

## 参考文档

- agy-worker 技能：[.agents/skills/agy-worker/SKILL.md](.agents/skills/agy-worker/SKILL.md)
- 工作流说明：[docs/AGY_WORKFLOW.md](docs/AGY_WORKFLOW.md)
- 任务模板：[.agents/skills/agy-worker/assets/task-plan.md](.agents/skills/agy-worker/assets/task-plan.md)（旧路径 [docs/agy/TASK_TEMPLATE.md](docs/agy/TASK_TEMPLATE.md) 已改为兼容指向）
- 命令例外：[docs/agy/COMMAND_EXCEPTION.md](docs/agy/COMMAND_EXCEPTION.md)
- 入口脚本：[scripts/agy.ps1](scripts/agy.ps1)（兼容入口，转发到 skill 实现）
