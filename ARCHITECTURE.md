# FDE AI Agent — 架构说明

面向桌面的 Android 智能体：**所选 AI 供应商（DeepSeek / 通义千问 / 智谱 GLM / Kimi / Gemini /
自定义 OpenAI 兼容）规划 → Skill Engine 编排 → AppFunctions / 内置能力执行，
全程由统一的执行事件驱动 SVG 状态面板**。

## 1. 五层职责

| 层 | 主要类 | 职责 |
| --- | --- | --- |
| 意图/规划 | `ai/AiProvider.kt`、`ai/AiClient.kt` | 多供应商 LLM 客户端（OpenAI 兼容 + Gemini）；理解用户意图；从 Skill 目录中选择 Skill 并填写入参（`run_skill` 工具），或直接回答 |
| Skill | `skill/SkillRegistry.kt`、`skill/SkillEngine.kt` | 装载 `assets/skills/**/SKILL.md`；把多个工具编排成可复用的多步骤流程 |
| AppFunctions | `appfunctions/AppFunctionCatalog.kt`、`appfunctions/AppFunctionDispatcher.kt` | 登记/分发 Android 应用暴露的真实能力（当前为相册 openImage / playVideo） |
| 权限与执行 | `execution/PermissionGuard.kt`、`execution/AgentCoordinator.kt` | 统一安全把关 + 流水线总协调 |
| SVG 状态面板 | `ui/SvgStateRenderer.kt` | 用统一执行事件渲染流程节点、日志尾部与错误提示（WebView 展示） |

`execution/AgentEvent.kt` 定义了统一执行事件（FlowStarted / NodeStarted / NodeFinished /
NodeSkipped / NodeFailed / Logged / FlowFinished / FlowFailed），
SVG 面板、运行日志、错误提示条都只订阅这一套事件。

## 2. 模块结构

```
app/src/main/
├── assets/skills/
│   ├── app_inspect/SKILL.md      # 检查 AppFunctions 环境并打开图片/播放视频（含权限检查）
│   ├── file_search/SKILL.md      # 目录内按规则搜索文件（内置工具）
│   └── task_create/SKILL.md      # 写入任务文件并回读校验（演示权限守卫 + 引用链）
└── java/com/fde/aiagent/
    ├── MainActivity.kt              # 纯 UI：选择供应商/填 key、订阅事件、驱动 SVG/日志/错误提示
    ├── ai/
    │   ├── AiProvider.kt            # 供应商预设（DeepSeek/千问/智谱/Kimi/Gemini/自定义）+ AiConfig
    │   └── AiClient.kt              # LLM 客户端 + 规划（OpenAI 兼容 + Gemini 协议，不直接执行任何工具）
    ├── skill/
    │   ├── Skill.kt                 # Skill / SkillStep 模型
    │   ├── SkillFrontMatter.kt      # 最小 YAML front-matter 解析器
    │   ├── SkillParser.kt           # SKILL.md -> Skill
    │   ├── SkillRegistry.kt         # 从 assets 装载 Skill
    │   ├── SkillRefs.kt             # $input.x / ${step.output} 引用与 runWhen 条件求值（纯逻辑）
    │   ├── SkillEngine.kt           # 多步流程执行引擎（发布 AgentEvent）
    │   └── SkillBuiltins.kt         # 本地内置工具（file_scan / file_read_head / file_create）
    ├── appfunctions/
    │   ├── AppFunction.kt           # AppFunction 目录项 + 调用结果模型
    │   ├── AppFunctionCatalog.kt    # 能力目录
    │   └── AppFunctionDispatcher.kt # 平台 AppFunctionManager 跨应用调用
    ├── execution/
    │   ├── AgentEvent.kt            # 统一事件 + 流程节点模型
    │   ├── ExecutionBus.kt          # 事件总线 / 监听器
    │   ├── PermissionGuard.kt       # 权限守卫（EXECUTE_APP_FUNCTIONS / CONTROL_SYSTEM）
    │   └── AgentCoordinator.kt      # 总协调：runAgent / 直接调用 / 权限摘要
    └── ui/SvgStateRenderer.kt       # AgentEvent -> SVG 字符串
```

### 2.1 AI 供应商

左上角「AI 供应商」下拉框选择，API Key / 模型均可手动输入并持久化（SharedPreferences），默认模型：

| 供应商 | 协议 | 默认模型 |
| --- | --- | --- |
| DeepSeek（深度求索） | OpenAI 兼容 `chat/completions` | `deepseek-chat` |
| 通义千问（Qwen） | OpenAI 兼容 `chat/completions`（DashScope 兼容模式） | `qwen-plus` |
| 智谱 AI（GLM） | OpenAI 兼容 `chat/completions` | `glm-4-plus` |
| Kimi（月之暗面） | OpenAI 兼容 `chat/completions` | `moonshot-v1-8k` |
| Google Gemini | `generateContent`（functionDeclarations / functionCall） | `gemini-2.5-flash` |
| 自定义 OpenAI 兼容 | `chat/completions`（需填 Base URL） | `gpt-4o` |

新增一家 OpenAI 兼容的供应商只需在 `AiProvider.kt` 枚举里加一项；非兼容协议（如 Gemini）
需在 `AiClient.kt` 中增加对应的请求 / 解析分支（已内置 Gemini，可直接参照扩展）。

## 3. 一次「打开图片」的执行时序

```
MainActivity.btnRunAgent
  └─ AgentCoordinator.runAgent(cfg, prompt)
       ├─ publish(FlowStarted)
       ├─ publish(PLAN 节点) → AiClient.plan(prompt, skills)
       │    按选中的供应商切换协议:
       │      OpenAI 兼容（DeepSeek/千问/智谱/Kimi）→ chat/completions + tools
       │      Gemini → generateContent + functionDeclarations
       │    模型决策:
       │      ─ run_skill(skill=app_inspect, inputs={path,title,video,...})
       │      ─ 或直接回答（闲聊/无匹配）
       ├─ SkillEngine.execute(app_inspect, inputs, bus)
       │    1. check_platform   -> PermissionGuard.checkAppFunctionExecution()
       │    2. open_image       -> AppFunctionDispatcher.dispatch(openImage,{path,title})
       │    3. play_video       -> run_when('$input.video != ""') 不满足则 SKIPPED
       │    4. report           -> 汇总前三步输出
       │    每一步都 publish(NodeStarted/NodeFinished/NodeFailed/NodeSkipped/Logged)
       └─ publish(FlowFinished / FlowFailed)
```

UI 侧（`MainActivity` 作为 `ExecutionListener`）在同一 `onEvent` 回调里
同时更新：SVG 状态面板（`SvgStateRenderer`）、运行日志控制台、`tvError` 错误提示条、`tvStatus` 状态胶囊。

「打开相册」按钮不经过模型，直接走 `AgentCoordinator.openImage()` —— 依旧发布相同事件，
因此同样的 SVG 流程/日志/错误面板都能正确显示。

## 4. Skill 存储格式（Markdown + YAML 元数据）

`SKILL.md` 头部是 YAML front-matter（`--- ... ---`），正文是 Markdown 说明（可注入模型上下文）。
支持的子集语法见 `SkillFrontMatter.kt`。

```markdown
---
name: app_inspect
description: ...            # 给所选 AI 模型的描述
inputs:
  path: 图片绝对路径         # 声明参数（name -> 说明）
input_required:
  - path
steps:
  - id: open_image
    type: function          # check | function | builtin | report
    function: openImage     # AppFunctionCatalog 中的 id
    inputs: { path: "$input.path" }
  - id: play_video
    type: function
    function: playVideo
    run_when: '$input.video != ""'   # 条件不满足则节点标记为 SKIPPED
  - id: report
    type: report
    template: "... ${play_video.output}"
---
```

引用语法：
- `$input.name` —— 用户（/模型）为该 Skill 填写的入参；
- `${stepId.output}` —— 前一步的输出，未执行的步骤显示为 `(跳过:stepId)`。

`runWhen` 支持 `true`/`false`、`$input.x`（非空）、`$input.x != ""`、`$input.x == "v"`。

## 5. 权限与执行层

- `PermissionGuard` 是全应用唯一的把关点：AppFunctions 是否受支持（API≥36）、是否持有
  `EXECUTE_APP_FUNCTIONS`、本地敏感写入是否允许（`CONTROL_SYSTEM`）。
- `AppFunctionDispatcher` 的每次调用都会先经过 `PermissionGuard`，再走平台
  `AppFunctionManager` 跨应用执行。
- `SkillBuiltins.file_create` 对应用私有目录之外写入同样经过守卫；未授权则步骤失败并给出可读原因。

## 6. 构建

- Gradle：`app/build.gradle`，依赖不变（OkHttp + Gson + coroutines 等树内库）。
- Soong：`Android.bp` 已含 `asset_dirs: ["app/src/main/assets"]`，Skill 资源自动打包，无需改动。
