---
name: curated_apps
version: 1.0.0
description: 操作 FDE 精选应用商店（精选应用商店应用 com.fde.download，FDECuratedApps）：列出精选应用及其安装/更新状态（getAvailableApps）、后台下载并静默安装某个精选应用（downloadAndInstallApp，按包名或精选显示名识别）；例如“有什么精选应用可以安装”“帮我安装微信”“从精选商店安装 com.tencent.mm”
tags:
  - apps
  - app
  - install
  - download
  - store
  - curated
  - appfunctions
functions:
  - getAvailableApps
  - downloadAndInstallApp
inputs:
  list: 是否列出 FDE 精选应用商店中的应用（查询模式，填 true；与 install 二选一），例如“有什么精选应用可以安装”
  install: 要后台下载并静默安装的精选应用（安装模式，填包名如 com.tencent.mm 或精选显示名如 微信；与 list 二选一），例如“帮我安装微信”
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: list_apps
    type: function
    function: com.fde.download.appfunctions.CuratedAppsAppFunctions#getAvailableApps
    description: 列出 FDE 精选应用商店中的应用及安装/更新状态（list 填 true 时执行，无参数）
    run_when: '$input.list != ""'
  - id: install_app
    type: function
    function: com.fde.download.appfunctions.CuratedAppsAppFunctions#downloadAndInstallApp
    description: 后台下载并静默安装精选应用（install 非空时执行，按包名/显示名识别）
    run_when: '$input.install != ""'
    inputs:
      packageName: "$input.install"
      appName: "$input.install"
  - id: report
    type: report
    template: "精选应用结果：查询列表 => ${list_apps.output}；安装应用 => ${install_app.output}"
---

# curated_apps — FDE 精选应用商店（列出精选应用 / 后台安装）

## 目的

当用户想查看或安装 FDE 精选应用商店中的应用（例如「有什么精选应用可以安装」
「帮我安装微信」）时，本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.fde.download`（FDECuratedApps，
   androidx.appfunctions-service 自动注册的 AppFunction 服务）暴露的 AppFunction：
   - `getAvailableApps`：列出精选应用，带包名、curated 版本与当前安装/更新状态；
   - `downloadAndInstallApp`：后台下载 + 静默安装某个精选应用（按包名或显示名识别），
     调用立即返回，下载与安装异步继续。
3. 汇总结果，形成可读的中文输出。

## 功能与参数

### 1. 列出精选应用（getAvailableApps）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `list` | 与 `install` 二选一 | 填 `true` |

无参数调用，返回每个精选应用的 `name`（精选显示名）、`packageName`、`version`（curated
版本）、`installed`（是否已安装）、`updateAvailable`（已装旧版本是否可更新）。

### 2. 后台下载 + 静默安装（downloadAndInstallApp）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `install` | 与 `list` 二选一 | 包的 `packageName`（如 `com.tencent.mm`）或精选显示名（如 `微信`） |

安装标识会同时作为 `packageName` 与 `appName` 下发，商店侧复用下载 UI 的同一份精选
列表：**包装名优先、显示名其次**解析，因此传包名或精选显示名都能命中。后台提交下载
（MD5 校验）+ 静默安装后立即返回。响应带 `submitted` 与 `status`：

- `status="submitted"`：已提交后台任务（下载并静默安装继续异步进行）；
- `status="already_installed"`：已安装且是最新 curated 版本，本调用为 no-op。

### 等效的 cmd 用法（app_function 命令行直调）

也可以通过 `app_function` 命令直接调用，效果与本 Skill 一致（函数标识符为 KSP 生成的
「类全名#函数名」完整 ID，与 AppFunctionCatalog 中的 id 一致）：

```shell
# 列出精选应用
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.fde.download \
  --function 'com.fde.download.appfunctions.CuratedAppsAppFunctions#getAvailableApps'

# 安装微信（按精选显示名）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.fde.download \
  --function 'com.fde.download.appfunctions.CuratedAppsAppFunctions#downloadAndInstallApp' \
  --parameters '{"appName":"微信"}'

# 安装微信（或直接给包名）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.fde.download \
  --function 'com.fde.download.appfunctions.CuratedAppsAppFunctions#downloadAndInstallApp' \
  --parameters '{"packageName":"com.tencent.mm"}'
```

## 行为约束

- `list` 与 `install` 二选一：填 `list=true` 只查询列表；填 `install=...` 只发起安装。
  两个都填则两个步骤都会执行；查找不到匹配精选应用时会失败并提示。
- 安装是「提交」语义：`status="submitted"` 表示后台任务已排入队列，下载与安装**异步**
  继续，本调用不阻塞到安装完成；已是最新版本时返回 `already_installed` 不会重复下载。
- 找不到匹配的精选应用（包名/显示名均不匹配）时商店侧抛
  `AppFunctionInvalidArgumentException`，分发层会将其识别为失败并提示。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户。
- 本 Skill 只查看/安装 FDE 精选应用商店中的应用；打开已安装的应用请使用 app_open Skill。
