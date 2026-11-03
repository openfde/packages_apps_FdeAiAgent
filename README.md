# FDE AI Agent — 架构说明

面向桌面的 Android 智能体：**所选 AI 供应商（DeepSeek / 通义千问 / 智谱 GLM / Kimi / Gemini /
自定义 OpenAI 兼容）规划 → Skill Engine 编排 → AppFunctions / 内置能力执行，
全程由统一的执行事件驱动 SVG 状态面板**。

## 1. 五层职责

| 层 | 主要类 | 职责 |
| --- | --- | --- |
| 意图/规划 | `ai/AiProvider.kt`、`ai/AiClient.kt` | 多供应商 LLM 客户端（OpenAI 兼容 + Gemini）；理解用户意图；从 Skill 目录中选择一个或多个 Skill 并填写入参（`run_skills` 工具，tasks 数组可承载一次请求里的多条指令），或直接回答 |
| Skill | `skill/SkillRegistry.kt`、`skill/SkillEngine.kt` | 装载 `assets/skills/**/SKILL.md`；把多个工具编排成可复用的多步骤流程 |
| AppFunctions | `appfunctions/AppFunctionCatalog.kt`、`appfunctions/AppFunctionDispatcher.kt` | 登记/分发 Android 应用暴露的真实能力（相册 openImage / playVideo / editImage、系统设置 openSettingsPage / adjustBrightness） |
| 权限与执行 | `execution/PermissionGuard.kt`、`execution/AgentCoordinator.kt` | 统一安全把关 + 流水线总协调 |
| SVG 状态面板 | `ui/SvgStateRenderer.kt` | 用统一执行事件渲染流程节点、日志尾部与错误提示（WebView 展示） |

`execution/AgentEvent.kt` 定义了统一执行事件（FlowStarted / NodeStarted / NodeFinished /
NodeSkipped / NodeFailed / Logged / FlowFinished / FlowFailed），
SVG 面板、运行日志、错误提示条都只订阅这一套事件。

## 2. 模块结构

```
app/src/main/
├── assets/skills/
│   ├── gallery/SKILL.md         # 相册媒体操作：打开图片/播放视频/图片滤镜编辑（openImage/playVideo/editImage，含权限检查）
│   ├── file_search/SKILL.md      # 目录内按规则搜索文件（内置工具）
│   ├── file_op/SKILL.md          # 文件管理操作（DocumentsUI）：删除移入回收站（deleteFile）、移动文件（moveFiles）、重命名（renameFolder）、清空回收站（emptyTrash）
│   ├── settings/SKILL.md        # 系统设置控制：打开设置页 wifi/display/sound/apps/storage（openSettingsPage）+ 调节屏幕亮度（adjustBrightness）+ 调节媒体音量（adjustMediaVolume）+ 开启/关闭深色模式（setDarkTheme）
│   ├── app_open/SKILL.md        # 打开应用：按包名或应用名打开（openApp，经 Settings 的 OpenSettingsPageAppFunctionService）
│   ├── task_create/SKILL.md      # 写入任务文件并回读校验（演示权限守卫 + 引用链）
│   ├── file_find/SKILL.md        # 查找文件（DocumentsUI 只读 AppFunction）：按名称/时间过滤条件查找（findFiles）、找大文件（findLargeFiles）、找 N 天未用文件（findUnusedFiles）
│   └── file_delete/SKILL.md      # 批量删除文件（DocumentsUI）：按名称前缀/后缀删除（deleteFilesByName）、按修改时间删除（deleteFilesByTime）、删除显式路径（deleteFiles）
│   └── curated_apps/SKILL.md     # FDE 精选应用商店（com.fde.download，FDECuratedApps）：列出精选应用及安装/更新状态（getAvailableApps）、后台下载+静默安装（downloadAndInstallApp）
├── assets/sherpa/zh14m/          # 纯离线中文语音识别模型（流式 zipformer，int8）：encoder/decoder/joiner/tokens
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
    ├── voice/
    │   └── SherpaVoiceRecognizer.kt # 纯离线中文语音识别（sherpa-onnx JNI + AudioRecord）
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

### 2.2 系统设置控制（settings：openSettingsPage / adjustBrightness / adjustMediaVolume / setDarkTheme）

系统设置应用 `com.android.settings` 通过 `OpenSettingsPageAppFunctionService`
（`assets/fdesettings_appfunctions.xml` 声明，见 Settings 源码）对外开放五个 AppFunction：

- `openSettingsPage`：打开指定设置页，参数 `page` 取值与 FdeSystemControl 的
  `SystemControlService.openSettingsPage` 保持一致：`wifi` / `display` / `sound` / `apps` / `storage`。
- `adjustBrightness`：调节屏幕亮度，参数 `action`（`brighten` 调亮 / `dim` 调暗）必填，
  `step` 可选（每次调节的亮度百分比，默认 10，范围 1~100）。亮度换算逻辑与
  `src/com/android/settings/display/BrightnessLevelPreference.kt` 一致。
- `adjustMediaVolume`：调节媒体音量 / 静音，参数 `action`（`volume_up` 调高 / `volume_down`
  调低 / `mute` 静音 / `unmute` 取消静音）必填，`step` 可选（每次调节的音量百分比，默认 10，
  范围 1~100，**仅对** `volume_up` / `volume_down` 生效），映射到媒体音量
  `[getStreamMinVolume, getStreamMaxVolume]` 分档范围，通过 `AudioManager#setStreamVolume`
  写回；`mute` / `unmute` 通过 `AudioManager#adjustStreamVolume(ADJUST_MUTE / ADJUST_UNMUTE)`
  切换静音状态（幂等无副作用）。
- `setDarkTheme`：开启/关闭深色模式，参数 `action`（`on` 开启 / `off` 关闭）必填，通过
  `UiModeManager#setNightModeActivated` 切换，行为与 `DarkModeActivationPreferenceController` 一致。
- `openApp`：按包名或应用名打开某个应用。参数 `packageName`（可选）或 `appName`（可选），
  至少提供一个，同时提供时以 `packageName` 优先：`packageName` 直接走
  `getLaunchIntentForPackage`；`appName` 对所有可启动应用做忽略大小写的精确/包含匹配，
  命中唯一应用后打开。

其中 `adjustBrightness` / `adjustMediaVolume` 结果带 `before` / `after` 百分比，
`adjustMediaVolume` 另带 `muted`（调节后的静音状态），
`setDarkTheme` 带 `enabled`，`openApp` 带解析后的 `packageName` / `appName`；分发器
会把它们拼进成功文案返回。注意：`openApp` 属于「apps」类别，FdeAiAgent 侧单独建模为
`app_open` Skill（见 2.4），避免与设置控制混淆。

命令行等价用法：
```
cmd app_function execute-app-function --brief-yaml \
  --package com.android.settings --function 'adjustBrightness' \
  --parameters '{"action":"brighten","step":50}'

cmd app_function execute-app-function --brief-yaml \
  --package com.android.settings --function 'adjustMediaVolume' \
  --parameters '{"action":"volume_down","step":"0"}'

cmd app_function execute-app-function --brief-yaml \
  --package com.android.settings --function 'setDarkTheme' \
  --parameters '{"action":"off"}'

cmd app_function execute-app-function --brief-yaml \
  --package com.android.settings --function 'openApp' \
  --parameters '{"packageName":"com.android.settings"}'
```

FdeAiAgent 侧只需做消费方接入：

- `AppFunctionCatalog` 增加 `OPEN_SETTINGS_PAGE` / `ADJUST_BRIGHTNESS` /
  `ADJUST_MEDIA_VOLUME` / `SET_DARK_THEME` / `OPEN_APP` 目录项（包名 / 服务名 / 参数 schema）；
- 合并为单个 Skill `settings`（`assets/skills/settings/SKILL.md`），使 AI 能规划
  「打开 WiFi 设置」「把屏幕调亮一点」「把声音调大一点」「打开深色模式」这类指令；
  `openApp` 单独建模为 Skill `app_open`（`assets/skills/app_open/SKILL.md`）；
- 结果文档按 `ok` / `status` / `error` 解析：`ok=false, error="unsupported_page"` /
  `error="invalid_action"` / `error="app_not_found"` 等会被 `AppFunctionDispatcher`
  识别为失败并反馈给用户。

### 2.3 相册媒体操作（gallery：openImage / playVideo / editImage）

相册应用 `com.fde.gallery3d` 通过 `MediaAppFunctionsService`
（`assets/appfunctions.xml` 声明，见 FdeGallery 源码）对外开放三个 AppFunction：

- `openImage`：打开图片预览，参数 `path`（必填）+ `title`（可选）；
- `playVideo`：播放视频，参数 `path`（必填）+ `title`（可选）；
- `editImage`：给图片添加滤镜并另存，参数 `path`（必填）+ `filter`（可选，缺省
  `black_white` 黑白，`origin` 表示不处理仅另存副本）+ `outputPath`（可选，缺省写入
  Pictures/fde 目录并返回实际路径）。处理逻辑参考 FdeGallery 的 `FilterListFragment`
  （采样加载 → 复制 ARGB_8888 → `PhotoProcessing.filterPhoto` → PNG 写回 + 相册扫描）。

FdeAiAgent 侧消费方接入：

- `AppFunctionCatalog` 增加 `OPEN_IMAGE` / `PLAY_VIDEO` / `EDIT_IMAGE` 目录项；
- 合并为单个 Skill `gallery`（`assets/skills/gallery/SKILL.md`），使 AI 能规划
  「打开这张图片」「播放这个视频」「给这张图加黑白滤镜另存」这类指令；
- 结果文档统一解析：`editImage` 成功时带 `outputPath`，分发器会把它拼进成功文案返回。

### 2.4 打开应用（app_open：openApp）

打开应用能力同样由系统设置应用 `com.android.settings` 的
`OpenSettingsPageAppFunctionService` 对外暴露（`openApp`，category 为 `apps`），
参数 `packageName` 或 `appName` 至少提供一个、同时提供时以 `packageName` 优先：
包名直接走 `getLaunchIntentForPackage`；应用名对所有可启动应用做忽略大小写的精确 /
包含匹配，命中唯一应用后打开。

FdeAiAgent 侧消费方接入：

- `AppFunctionCatalog` 增加 `OPEN_APP` 目录项；
- 单独建模为 Skill `app_open`（`assets/skills/app_open/SKILL.md`），使 AI 能规划
  「打开设置」「打开相册」这类指令（与 `settings` Skill 区分开，避免模型混淆）；
- 结果文档 `OpenAppResult` 带解析后的 `packageName` / `appName`，分发器会拼进成功文案。

### 2.5 文件管理操作（file_op：deleteFile / moveFiles / renameFolder / emptyTrash）

文件管理器 `com.android.documentsui` 通过 `DocumentsUiAppFunctionService`
（`assets/appfunctions.xml` 声明，见 DocumentsUI 源码）对外暴露**十个**文件 AppFunction：

- `deleteFile`：删除（移入系统回收站）文件/文件夹，可在回收站页面恢复，参数 `path`（必填）；
- `moveFiles`：把源文件夹下的所有文件/子文件夹移动到目标目录，参数 `sourcePath`（必填）、
  `destinationPath`（必填）；目标不存在时自动创建，已有同名项自动生成唯一名称避免覆盖，
  返回 `movedCount`；
- `renameFolder`：重命名文件/文件夹（对文件同样生效），参数 `path`（必填）+ `newName`
  （必填）；成功返回新路径 `newPath`；
- `emptyTrash`：清空系统回收站，永久删除其中全部内容，无参数；返回 `deletedCount`；
- `findFiles`：按名称/修改时间等过滤条件只读查找文件夹直接子项（只读，见 2.7）；
- `deleteFilesByName`：删除文件夹下以 `prefix` 开头 / `suffix` 结尾的所有文件（见 2.8）；
- `deleteFilesByTime`：删除文件夹下最后修改时间在 `before` / `after` 之间的文件（见 2.8）；
- `findLargeFiles`：递归找出大文件（只读，见 2.7）；
- `findUnusedFiles`：递归找出 N 天未使用的不常用文件（只读，见 2.7）；
- `deleteFiles`：删除显式列出的路径列表（见 2.8）。

FdeAiAgent 侧把全部十个都纳入 `AppFunctionCatalog`（`FILE_*` 目录项全部注册），并按职责拆成三个
Skill：本节的 `file_op` 覆盖前四个（新增 2.7 / 2.8 两个 Skill 覆盖后六个查找与批量删除）。

其中 `file_op` 覆盖的四个函数：

四个函数的路径参数均支持绝对路径（如 `/storage/emulated/0/Desktop/a.txt`），也支持相对
桌面（Desktop）的名称（如 `a.txt`，等价于 `/storage/emulated/0/Desktop/a.txt`），与
DocumentsUI 的 `IpcService` 约定保持一致。失败场景（参数缺失 / 文件不存在 / 目标非法等）
通过 `AppFunctionException` 返回，分发器会把可读原因反馈给用户。

FdeAiAgent 侧消费方接入：

- `AppFunctionCatalog` 增加 `DELETE_FILE` / `MOVE_FILES` / `RENAME_FOLDER` / `EMPTY_TRASH`
  目录项（包名 `com.android.documentsui` / 服务名 / 参数 schema）；
- 合并为单个 Skill `file_op`（`assets/skills/file_op/SKILL.md`），使 AI 能规划
  「删除 a.txt」「把临时文件夹里的文件移到归档」「把报告改名为报告2026」「清空回收站」
  这类指令（与只读的 `file_search` Skill 区分开）；
- 结果文档 `FileOpResult` 统一解析：`status="ok"`，按 `path` / `newPath` / `movedCount` /
  `deletedCount` 拼进成功文案与用户反馈。

### 2.6 语音输入（Voice Input · 完全离线）

用户可点击麦克风按钮，对着麦克风说话，**sherpa-onnx 纯离线中文识别**把语音转写成文字后：

- 自动填入「智能体指令（Prompt）」输入框（保留原文可先编辑）；
- 勾选「识别后自动运行」时直接触发 `AgentCoordinator.runAgent`（与手动运行完全一致），
  让语音可以驱动 Skill 引擎执行各种 skill 指令（打开应用/设置、文件操作、相册等）。

**完全离线**：识别模型（`sherpa-onnx-streaming-zipformer-zh-14M`，流式 zipformer-transducer，
中文 int8，约 25MB）内置在 APK assets 的 `assets/sherpa/zh14m/` 下，通过 JNI 在本地推理，
**不依赖任何网络与系统语音识别服务**，任何设备可用。

实现要点：

- 原生库：`libs/sherpa-onnx-2.0.1.aar`（sherpa-onnx v2.0.1，仅保留 arm64-v8a / armeabi-v7a
  的 `libonnxruntime.so` / `libsherpa-onnx-{c,cxx,jni}-api.so`）。Gradle 侧
  `implementation files('libs/...aar')`；Soong 侧 `android_library_import`（`extract_jni: true`）
  打进 `android_app` 的 `lib/<abi>/`。
- 封装类 `voice/SherpaVoiceRecognizer.kt`：
  - 后台线程懒加载 `OnlineRecognizer(assetManager, config)`，模型路径直接用 assets 相对路径
    （`sherpa/zh14m/...`），无需拷贝到私有目录；
  - `AudioRecord` 采集 16kHz 单声道 16-bit PCM，每 100ms 一块 `acceptWaveform(samples, 16000)`
    喂给流式解码，`onPartial` 实时回显中间文字；
  - 再次点击麦克风 `stop()` 后 `inputFinished()` + 回溯解码，得到整句最终结果。
- 语言：纯中文模型（单语），UI 不再提供语言下拉。界面文案全部走 `@string/…`（中/英文资源）。
- 权限：`AndroidManifest.xml` 声明 `RECORD_AUDIO`（运行时权限），首次点麦克风时
  `requestPermissions` 申请；拒绝/硬件不可用/模型缺失等都有可读的本地化提示显示在 `tvVoiceStatus`。
- 交互：聆听中按钮变「⏹ 停止」，再次点击停止并输出结果；识别成功自动填入指令框，
  自动运行开关随 `saveSettings()` 持久化。

### 2.7 查找文件（file_find：findFiles / findLargeFiles / findUnusedFiles）

DocumentsUI 还暴露三个**只读**查找 AppFunction，FdeAiAgent 合并为一个 Skill `file_find`
（`assets/skills/file_find/SKILL.md`）：

- `findFiles`：参数 `folder`（必填）+ 可选过滤条件（`namePrefix` / `nameSuffix` 名称过滤；
  `lastMonths` 最近 N 个月修改、`beforeDate` / `afterDate` 修改时间过滤；`limit` 条数），只读
  扫描 `folder` 的**直接子项**，按修改时间倒序返回 `count` + `matches`（路径列表）；
- `findLargeFiles`：参数 `path`（必填）+ `minSize`（字节，缺省 100MB）+ `recursive` +
  `limit`（缺省 50），递归找出大文件并按大小降序返回；
- `findUnusedFiles`：参数 `path`（必填）+ `days`（缺省 30）+ `limit`（缺省 50），递归找出
  N 天内未访问/未修改的不常用文件，按最久未使用在前返回。

三个查找只读不写；结果带 `count`，分发器解析 `matches` / `paths` 拼进成功文案。

### 2.8 批量删除文件（file_delete：deleteFilesByName / deleteFilesByTime / deleteFiles）

DocumentsUI 另提供三个删除 AppFunction，FdeAiAgent 合并为一个 Skill `file_delete`
（`assets/skills/file_delete/SKILL.md`）：

- `deleteFilesByName`：参数 `path`（必填）+ `prefix` / `suffix`（至少一个）+ `recursive`
  （缺省 true）+ `requireConfirmation`（缺省 true）——删除文件夹下以指定前缀开头/后缀结尾的
  所有文件；
- `deleteFilesByTime`：参数 `path`（必填）+ `before` / `after`（至少一个，Unix epoch 毫秒）
  + `recursive` + `requireConfirmation`——删除最后修改时间满足条件的文件；
- `deleteFiles`：参数 `paths`（必填，本版本以单个路径字符串下发）+ `requireConfirmation`——
  删除显式列出的路径。

三者移入系统回收站可恢复；`requireConfirmation` 缺省 true 时删除前弹窗逐项确认，用户取消时
返回 `cancelled=true`（分发器会反馈给用户）。删除结果带 `deletedCount`（+ 最多 200 条 `paths`）。

### 2.9 FDE 精选应用商店（curated_apps：getAvailableApps / downloadAndInstallApp）

FDE 精选应用商店应用 `com.fde.download`（FDECuratedApps）通过 androidx.appfunctions-service
（KSP 生成的 AppFunctionService 由库 manifest 自动注册）对外暴露两个 AppFunction，
FdeAiAgent 合并为 Skill `curated_apps`（`assets/skills/curated_apps/SKILL.md`），
功能的实现参考 FDECuratedApps 的 `src/com/fde/download/appfunctions/CuratedAppsAppFunctions.kt`：

- `getAvailableApps`：无参数，返回精选应用列表（与下载 UI 使用同一份 `CuratedAppsRepository`
  列表：网络优先、raw resource 兜底），每项带 `name`（精选显示名）/ `packageName` /
  `version`（curated 版本）/ `installed`（是否已安装）/ `updateAvailable`（已装旧版本是否可更新）；
- `downloadAndInstallApp`：参数 `packageName` / `appName` 至少提供一个（同时提供以
  `packageName` 优先），按商店内 `findApp` 解析（包名匹配优先，再按 curated 显示名匹配，
  支持 `微信` / `FDE_txt` 等写法）；已安装最新版本时返回 `status="already_installed"`（no-op），
  否则复用 `DownloadService` 管线后台下载（MD5 校验）+ 静默安装并立即返回
  `status="submitted"`；找不到匹配应用或参数缺失时抛 `AppFunctionInvalidArgumentException`。

FdeAiAgent 侧消费方接入：

- `AppFunctionCatalog` 增加 `GET_AVAILABLE_APPS` / `DOWNLOAD_AND_INSTALL_APP` 目录项。
  注意：androidx KSP 编译出的函数标识符是「类全名#函数名」完整 ID（如
  `com.fde.download.appfunctions.CuratedAppsAppFunctions#downloadAndInstallApp`），
  与 Settings / DocumentsUI 那些手写服务的短 ID 不同，目录项的 `id` 必须用完整 ID，
  平台 `AppFunctionManager` 按 `packageName + functionIdentifier` 解析；服务名填
  androidx.appfunctions-service 注册的 `androidx.appfunctions.service.PlatformAppFunctionService`；
- 合并为单个 Skill `curated_apps`，`install` 入参同时以 `packageName` + `appName` 下发，
  以同时覆盖「按包名」与「按精选显示名」两种识别方式；
- 结果文档统一解析：`downloadAndInstallApp` 带 `submitted` / `status` / `message` /
  `version`；`getAvailableApps` 带 `apps`（`getPropertyDocumentArray` 展开的对象数组）与
  `isTruncated`，分发器把每项拼成 `名称(包名) v版本 状态` 返回。

## 3. 一次「打开图片」的执行时序

```
MainActivity.btnRunAgent
  └─ AgentCoordinator.runAgent(cfg, prompt)
       ├─ publish(FlowStarted)
       ├─ publish(PLAN 节点) → AiClient.plan(prompt, skills)
       │    按选中的供应商切换协议:
       │      OpenAI 兼容（DeepSeek/千问/智谱/Kimi）→ chat/completions + tools
       │      Gemini → generateContent + functionDeclarations
       │    模型决策（一次请求可含多条指令，run_skills 的 tasks 数组一次承载多个任务）:
       │      ─ run_skills(tasks=[{skill=gallery, inputs={path,title,video,filter,...}}, ...])
       │      ─ 或直接回答（闲聊/无匹配）
       ├─ AgentCoordinator 按顺序执行每个任务:
       │    SkillEngine.execute(gallery, inputs, bus)   # 每个 Skill 任务独立执行、独立汇总
       │    SkillEngine.execute(settings, inputs, bus)  # 例：关闭深色模式 + 亮度调到最高时可能拆成多个任务
       │    1. check_platform   -> PermissionGuard.checkAppFunctionExecution()
       │    2. open_image       -> run_when('$input.filter == ""') 满足则
       │                            AppFunctionDispatcher.dispatch(openImage,{path,title})
       │    3. edit_image       -> run_when('$input.filter != ""') 满足则
       │                            dispatch(editImage,{path,filter,outputPath})
       │    4. play_video       -> run_when('$input.video != ""') 不满足则 SKIPPED
       │    5. report           -> 汇总前几步输出
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
name: gallery
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
