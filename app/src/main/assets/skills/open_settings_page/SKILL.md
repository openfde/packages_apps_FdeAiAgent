---
name: open_settings_page
version: 1.0.0
description: 打开指定的系统设置页面（支持 wifi / display / sound / apps / storage），通过系统设置应用 com.android.settings 暴露的 AppFunction openSettingsPage 实现
tags:
  - settings
  - system
  - appfunctions
functions:
  - openSettingsPage
inputs:
  page: 要打开的系统设置页 key：wifi（Wi-Fi 设置）/ display（显示）/ sound（声音）/ apps（应用管理）/ storage（存储）
input_required:
  - page
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: open_settings
    type: function
    function: openSettingsPage
    description: 跨应用打开指定的系统设置页
    inputs:
      page: "$input.page"
  - id: report
    type: report
    template: "已请求打开系统设置页 '$input.page'：${open_settings.output}"
---

# open_settings_page — 打开系统设置页面

## 目的

当用户想打开某个系统设置页面（例如「Wi-Fi 设置」「显示设置」）时，本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.android.settings` 的
   `OpenSettingsPageAppFunctionService` 暴露的 `openSettingsPage` AppFunction
   （参数 `page`，功能定义见 Settings 的 `assets/fdesettings_appfunctions.xml`）；
3. 汇总结果，形成可读的中文输出。

## 行为约束

- `page` 是必填参数，取值限定为：`wifi` / `display` / `sound` / `apps` / `storage`，
  与 FdeSystemControl 的系统设置打开行为保持一致。
- 设置侧对未知 `page` 会返回 `ok=false, error="unsupported_page"`，分发层会将其识别为失败并提示。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户（而不是继续执行）。
- 本 Skill 只打开设置页，不做系统设置的读取 / 修改。
