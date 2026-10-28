---
name: settings
version: 1.2.0
description: 控制系统设置：打开指定的系统设置页面（wifi / display / sound / apps / storage），调节屏幕亮度（调亮 brighten / 调暗 dim）、调节媒体音量（调高 volume_up / 调低 volume_down / 静音 mute / 取消静音 unmute）、开启或关闭深色模式（on / off），通过系统设置应用 com.android.settings 暴露的 AppFunction openSettingsPage / adjustBrightness / adjustMediaVolume / setDarkTheme 实现
tags:
  - settings
  - system
  - appfunctions
functions:
  - openSettingsPage
  - adjustBrightness
  - adjustMediaVolume
  - setDarkTheme
inputs:
  page: 要打开的系统设置页 key：wifi（Wi-Fi 设置）/ display（显示）/ sound（声音）/ apps（应用管理）/ storage（存储）（打开设置页时必填）
  action: 亮度调节方向（必填）：brighten（调亮）/ dim（调暗），例如“把屏幕调亮一点”填 brighten、“把屏幕调暗一点”填 dim
  step: 亮度调节百分比（可选，默认 10，范围 1~100；例如 step=50 表示一次调亮/调暗 50%）
  volume_action: 媒体音量操作（音量相关指令必填）：volume_up（调高）/ volume_down（调低）/ mute（静音）/ unmute（取消静音），例如“把声音调大一点”填 volume_up、“把声音调小一点”填 volume_down、“静音”填 mute、“取消静音”填 unmute
  volume_step: 音量调节百分比（可选，默认 10，范围 1~100；仅 volume_up / volume_down 生效，例如 volume_step=30 表示一次调高/调低 30%）
  dark_theme: 深色模式开关（深色模式相关指令必填）：on（开启深色模式）/ off（关闭深色模式），例如“打开深色模式”填 on、“关闭深色模式”填 off
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
  - id: adjust_volume
    type: function
    function: adjustMediaVolume
    description: 跨应用调节媒体音量或静音（volume_action 为 volume_up / volume_down / mute / unmute 时执行）
    run_when: '$input.volume_action != ""'
    inputs:
      action: "$input.volume_action"
      step: "$input.volume_step"
  - id: set_dark_theme
    type: function
    function: setDarkTheme
    description: 跨应用开启/关闭深色模式（dark_theme 为 on / off 时执行）
    run_when: '$input.dark_theme != ""'
    inputs:
      action: "$input.dark_theme"
  - id: report
    type: report
    template: "系统设置操作结果：打开设置页 => ${open_settings.output}；调节亮度 => ${adjust_brightness.output}；调节音量 => ${adjust_volume.output}；深色模式 => ${set_dark_theme.output}"
---

# settings — 系统设置控制（打开设置页 / 调节亮度 / 调节音量 / 深色模式）

## 目的

当用户想让智能体操作系统设置（例如「打开 Wi-Fi 设置」「把屏幕调亮一点」「把声音调大一点」
「打开深色模式」）时，本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.android.settings` 的
   `OpenSettingsPageAppFunctionService` 暴露的 AppFunction：
   - `openSettingsPage`：打开指定设置页，参数 `page`；
   - `adjustBrightness`：调节屏幕亮度，参数 `action`（brighten/dim）+ 可选 `step`；
   - `adjustMediaVolume`：调节媒体音量 / 静音，参数 `action`（volume_up/volume_down/mute/unmute）+ 可选 `step`（仅调高/调低生效）；
   - `setDarkTheme`：开启/关闭深色模式，参数 `action`（on/off）。
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

### 3. 调节媒体音量 / 静音（adjustMediaVolume）

| 参数 | 必填 | 取值 |
| --- | --- | --- |
| `action` | 是 | `volume_up`（调高）/ `volume_down`（调低）/ `mute`（静音）/ `unmute`（取消静音） |
| `step` | 否（默认 10） | 每次调节的音量百分比，1~100；**仅对** `volume_up` / `volume_down` 生效 |

音量读写基于 `AudioManager` 的 `STREAM_MUSIC` 流：读当前分档
`getStreamVolume`，把 `step` 个百分比换算为分档步长并在
`[getStreamMinVolume, getStreamMaxVolume]` 范围内，再通过
`AudioManager#setStreamVolume` 写回；`mute` / `unmute` 通过
`AudioManager#adjustStreamVolume(ADJUST_MUTE / ADJUST_UNMUTE)` 切换静音状态，对已处于
目标状态的情况幂等无副作用（静音不改变音量数值）。返回结果带调节前后的音量百分比
（before/after）以及调节后的静音状态 `muted`（分发器会拼进成功文案）。

### 4. 深色模式（setDarkTheme）

| 参数 | 必填 | 取值 |
| --- | --- | --- |
| `action` | 是 | `on`（开启深色模式）/ `off`（关闭深色模式） |

通过 `UiModeManager#setNightModeActivated(boolean)` 切换，行为与
`DarkModeActivationPreferenceController` 一致。

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

# 把媒体音量调大一些
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'adjustMediaVolume' \
  --parameters '{"action":"volume_up","step":10}'

# 把媒体音量调小一些
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'adjustMediaVolume' \
  --parameters '{"action":"volume_down","step":"0"}'

# 静音（媒体音量静音）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'adjustMediaVolume' \
  --parameters '{"action":"mute","step":"0"}'

# 取消静音（恢复媒体音量）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'adjustMediaVolume' \
  --parameters '{"action":"unmute","step":"0"}'

# 开启深色模式
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'setDarkTheme' \
  --parameters '{"action":"on"}'

# 关闭深色模式
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.settings \
  --function 'setDarkTheme' \
  --parameters '{"action":"off"}'
```

## 行为约束

- 打开设置页 / 调节亮度 / 调节音量 / 深色模式是相互独立的操作：填了哪个参数就执行对应的
  功能，未填写的操作对应的步骤会被跳过（标记为 SKIPPED）。
- `page` 取值限定为：`wifi` / `display` / `sound` / `apps` / `storage`；设置侧对未知
  `page` 会返回 `ok=false, error="unsupported_page"`，分发层会将其识别为失败并提示。
- 亮度 `action` 取值限定为：`brighten` / `dim`；音量 `action`（即 `volume_action`）取值限定
  为：`volume_up` / `volume_down` / `mute` / `unmute`；深色模式 `action` 取值限定为
  `on` / `off`。其它取值设置侧都会返回 `ok=false, error="invalid_action"`。
- 音量 `step` 仅对 `volume_up` / `volume_down` 生效；`mute` / `unmute` 不改变音量数值，
  只切换媒体流的静音状态且幂等（已静音再 mute、未静音再 unmute 均无副作用）。
- `step` 为整数百分比，越界会被限制在 1~100 之间，缺省按 10 处理。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户（而不是继续执行）。
- 本 Skill 只打开设置页 / 调节亮度 / 调节音量 / 切换深色模式，不做其它系统设置的读取 / 修改；
  打开某个应用请使用 app_open Skill（openApp）。
