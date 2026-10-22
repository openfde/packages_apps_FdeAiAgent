---
name: settings
version: 1.0.0
description: 控制系统设置：打开指定的系统设置页面（wifi / display / sound / apps / storage），或调节屏幕亮度（调亮 brighten / 调暗 dim），通过系统设置应用 com.android.settings 暴露的 AppFunction openSettingsPage / adjustBrightness 实现
tags:
  - settings
  - system
  - appfunctions
functions:
  - openSettingsPage
  - adjustBrightness
inputs:
  page: 要打开的系统设置页 key：wifi（Wi-Fi 设置）/ display（显示）/ sound（声音）/ apps（应用管理）/ storage（存储）（打开设置页时必填）
  action: 亮度调节方向（必填）：brighten（调亮）/ dim（调暗），例如“把屏幕调亮一点”填 brighten、“把屏幕调暗一点”填 dim
  step: 每次调节的亮度百分比（可选，默认 10，范围 1~100；例如 step=50 表示一次调亮/调暗 50%）
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: open_settings
    type: function
    function: openSettingsPage
    description: 跨应用打开指定的系统设置页（page 非空时执行）
    run_when: '$input.page != ""'
    inputs:
      page: "$input.page"
  - id: adjust_brightness
    type: function
    function: adjustBrightness
    description: 跨应用调节屏幕亮度（action 为 brighten / dim 时执行）
    run_when: '$input.action != ""'
    inputs:
      action: "$input.action"
      step: "$input.step"
  - id: report
    type: report
    template: "系统设置操作结果：打开设置页 => ${open_settings.output}；调节亮度 => ${adjust_brightness.output}"
---

# settings — 系统设置控制（打开设置页 / 调节屏幕亮度）

## 目的

当用户想让智能体操作系统设置（例如「打开 Wi-Fi 设置」「把屏幕调亮一点」「把屏幕调暗一点」）时，
本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.android.settings` 的
   `OpenSettingsPageAppFunctionService` 暴露的 AppFunction：
   - `openSettingsPage`：打开指定设置页，参数 `page`；
   - `adjustBrightness`：调节屏幕亮度，参数 `action`（brighten/dim）+ 可选 `step`。
3. 汇总结果，形成可读的中文输出。

## 功能与参数

### 1. 打开设置页（openSettingsPage）

| 参数 | 必填 | 取值 |
| --- | --- | --- |
| `page` | 是 | `wifi` / `display` / `sound` / `apps` / `storage` |

与 FdeSystemControl 的 `SystemControlService.openSettingsPage` 行为保持一致；
功能定义见 Settings 的 `assets/fdesettings_appfunctions.xml`。

### 2. 调节屏幕亮度（adjustBrightness）

| 参数 | 必填 | 取值 |
| --- | --- | --- |
| `action` | 是 | `brighten`（调亮）/ `dim`（调暗） |
| `step` | 否（默认 10） | 每次调节的亮度百分比，1~100 |

亮度读写的换算逻辑与
`src/com/android/settings/display/BrightnessLevelPreference.kt` 一致：
读取当前亮度到 gamma 空间换算成百分比后，在当前位置上增加/减少 `step` 个百分比，
再通过 `DisplayManager#setBrightness(..., BRIGHTNESS_UNIT_PERCENTAGE)` 写回。

### 等效的 cmd 用法（app_function 命令行直调）

也可以通过 `app_function` 命令直接调用，效果与本 Skill 一致：

```shell
# 把屏幕调亮 50%
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'adjustBrightness' \
  --parameters '{"action":"brighten","step":50}'

# 把屏幕调暗 50%
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'adjustBrightness' \
  --parameters '{"action":"dim","step":50}'
```

## 行为约束

- 「打开设置页」与「调节亮度」是两个相互独立的操作：填了 `page` 就打开对应设置页，
  填了 `action` 就调节亮度，未填写的操作对应的步骤会被跳过（标记为 SKIPPED）。
- `page` 取值限定为：`wifi` / `display` / `sound` / `apps` / `storage`；设置侧对未知
  `page` 会返回 `ok=false, error="unsupported_page"`，分发层会将其识别为失败并提示。
- `action` 取值限定为：`brighten` / `dim`；其它取值设置侧会返回
  `ok=false, error="invalid_action"`。
- `step` 为整数百分比，越界会被限制在 1~100 之间，缺省按 10 处理。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户（而不是继续执行）。
- 本 Skill 只打开设置页 / 调节亮度，不做其它系统设置的读取 / 修改。
