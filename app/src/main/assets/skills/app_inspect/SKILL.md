---
name: app_inspect
version: 1.0.0
# 检查 Android AppFunctions 平台能力（com.fde.gallery3d 相册应用），
# 并跨应用打开图片 / 播放视频。展示“权限守卫 → AppFunction 分发”的多步流程。
description: 检查 Android AppFunctions 平台能力并跨应用打开指定图片 / 播放视频（相册 com.fde.gallery3d）
tags:
  - media
  - appfunctions
  - gallery
functions:
  - openImage
  - playVideo
inputs:
  path: 要打开的图片绝对路径（例如 /mnt/sdcard/Pictures/aa.png）
  title: 图片显示标题（可选，默认取文件名）
  video: 要播放的视频绝对路径（可选，不填则跳过播放）
  video_title: 视频显示标题（可选）
input_required:
  - path
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: open_image
    type: function
    function: openImage
    description: 在相册中打开图片预览
    inputs:
      path: "$input.path"
      title: "$input.title"
  - id: play_video
    type: function
    function: playVideo
    description: 在相册中播放视频
    run_when: '$input.video != ""'
    inputs:
      path: "$input.video"
      title: "$input.video_title"
  - id: report
    type: report
    template: "已检查 AppFunctions 环境并执行媒体操作：openImage => ${open_image.output}；playVideo => ${play_video.output}"
---

# app_inspect — 应用环境检查 & 媒体操作

## 目的

当用户想“打开这张图片 / 播放那个视频”时，本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.fde.gallery3d` 的
   `MediaAppFunctionsService` 暴露的 `openImage` / `playVideo`；
3. 汇总每一步的输出，形成可读的中文结果。

## 行为约束

- `open_image` 是必做步骤；`play_video` 仅在 `video` 参数非空时执行。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户（而不是继续执行）。
- 本 Skill 不直接操作本地文件，只做跨应用的媒体打开/播放。
