---
name: app_open
version: 1.0.0
description: 打开手机上的某个应用，可按包名（packageName）或应用名（appName）打开，通过系统设置应用 com.android.settings 暴露的 AppFunction openApp 实现；例如“打开设置”“打开相册”“打开计算器”
tags:
  - apps
  - launch
  - appfunctions
functions:
  - openApp
inputs:
  packageName: 要打开应用的包名（可选），例如 com.android.settings；与 appName 至少提供一个，同时提供时以 packageName 优先
  appName: 要打开应用的名称（可选），例如“设置”“相册”，对所有可启动应用做忽略大小写的精确/包含匹配，命中唯一应用后打开
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: open_app
    type: function
    function: openApp
    description: 按包名/应用名打开目标应用（packageName 或 appName 至少一个）
    inputs:
      packageName: "$input.packageName"
      appName: "$input.appName"
  - id: report
    type: report
    template: "打开应用结果：${open_app.output}"
---

# app_open — 打开应用（按包名 / 应用名）

## 目的

当用户想让智能体打开某个应用（例如「打开设置」「打开相册」「打开微信」）时，本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.android.settings` 的
   `OpenSettingsPageAppFunctionService` 暴露的 `openApp` AppFunction：
   - 按包名 `packageName` 直接打开；
   - 或按应用名 `appName`（label）匹配后打开。
3. 汇总结果，形成可读的中文输出。

## 功能与参数

### 1. 打开应用（openApp）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `packageName` | 否（与 appName 至少一个） | 应用包名，例如 `com.android.settings`，直接通过 `getLaunchIntentForPackage` 打开 |
| `appName` | 否（与 packageName 至少一个） | 应用名称（label），例如「设置」，对所有可启动应用做忽略大小写的精确匹配；无精确命中时做包含匹配并取第一个命中项 |

同时提供 `packageName` 与 `appName` 时，以 `packageName` 优先；
结果文档会带解析出的 `packageName` / `appName`，分发器会把它们拼进成功文案返回。

### 等效的 cmd 用法（app_function 命令行直调）

也可以通过 `app_function` 命令直接调用，效果与本 Skill 一致：

```shell
# 按包名打开设置
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'openApp' \
  --parameters '{"packageName":"com.android.settings"}'

# 按应用名打开相册
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'openApp' \
  --parameters '{"appName":"相册"}'
```

## 行为约束

- `packageName` 与 `appName` 至少提供一个，都不填时设置侧会返回
  `ok=false, error="invalid_app"`，分发层将其识别为失败并提示。
- 包名 / 应用名都无法匹配到可启动应用时，设置侧返回 `ok=false, error="app_not_found"`。
- 应用名匹配对大小写不敏感：优先精确匹配，唯一命中即打开；无精确命中时做包含匹配并取
  第一个命中项（存在多个匹配时设置侧记录 warning 并取第一个）。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户（而不是继续执行）。
- 本 Skill 只打开应用，不做系统设置相关的调整；设置类操作请使用 settings Skill
  （openSettingsPage / adjustBrightness / adjustMediaVolume / setDarkTheme）。
