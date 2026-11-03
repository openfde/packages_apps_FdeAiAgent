---
name: task_create
version: 1.0.0
description: 将一段文本写入本地文件（任务记录），写完后回读校验，演示带权限守卫的多步写入流程
tags:
  - task
  - file
  - workflow
inputs:
  path: 要创建的文件绝对路径（例如 /data/local/tmp/demo_task.txt；也可以填应用私有目录）
  content: 要写入的文件内容文本
input_required:
  - path
steps:
  - id: check_permission
    type: check
    check: local_write
    description: 校验本地写入权限（权限守卫：CONTROL_SYSTEM 或应用私有目录）
  - id: create
    type: builtin
    action: file_create
    description: 写入任务文件
    inputs:
      path: "$input.path"
      content: "$input.content"
  - id: verify
    type: builtin
    action: file_read_head
    description: 回读文件开头进行校验
    inputs:
      path: "${create.output}"
      lines: "5"
  - id: report
    type: report
    template: "任务文件已创建并回读校验：${verify.output}"
---

# task_create — 创建任务文件（含权限守卫）

## 目的

把一段文本落盘成一个文件（例如记录一条待办任务），随后回读文件开头验证写入成功。

## 行为约束

- 写入 **应用私有目录之外** 的路径需要 `com.fde.permission.CONTROL_SYSTEM`（由
  `PermissionGuard` 拦截，未授权则该步骤失败并提示）。
- `create` 步骤的输出是最终写入的绝对路径，`verify` 步骤会回读该文件的前几行。
- 本 Skill 演示如何把“权限检查 → 写入 → 回读校验 → 汇总”多个步骤编排成可复用流程。
