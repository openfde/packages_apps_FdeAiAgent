---
name: gallery
version: 1.0.0
description: 操作相册媒体（com.fde.gallery3d）：打开图片预览（openImage）、播放视频（playVideo）、给图片添加滤镜并另存（editImage，支持黑白 black_white 等滤镜）
tags:
  - media
  - appfunctions
  - gallery
functions:
  - openImage
  - playVideo
  - editImage
inputs:
  path: 图片绝对路径（例如 /sdcard/Download/bella.jpg）（打开图片 / 编辑图片时必填）
  title: 图片显示标题（可选，默认取文件名）
  filter: 要应用的滤镜名称（编辑图片时填写；不编辑图片请不要填）：black_white / bw / gray（黑白）、sepia（怀旧）、retro（复古）、classic（经典）、soft（软化）、shine（发光）、mosaic（马赛克）、origin（仅另存副本不处理）等
  outputPath: 编辑结果的输出路径（可选，缺省写入 Pictures/fde 目录并返回实际路径）
  video: 要播放的视频绝对路径（可选，不填则跳过播放）
  video_title: 视频显示标题（可选）
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: open_image
    type: function
    function: openImage
    description: 在相册中打开图片预览（未指定滤镜时执行）
    run_when: '$input.filter == ""'
    inputs:
      path: "$input.path"
      title: "$input.title"
  - id: edit_image
    type: function
    function: editImage
    description: 给图片添加滤镜并保存（指定了 filter 时执行）
    run_when: '$input.filter != ""'
    inputs:
      path: "$input.path"
      filter: "$input.filter"
      outputPath: "$input.outputPath"
  - id: play_video
    type: function
    function: playVideo
    description: 在相册中播放视频（video 非空时执行）
    run_when: '$input.video != ""'
    inputs:
      path: "$input.video"
      title: "$input.video_title"
  - id: report
    type: report
    template: "媒体操作结果：openImage => ${open_image.output}；editImage => ${edit_image.output}；playVideo => ${play_video.output}"
---

# gallery — 相册媒体操作（打开图片 / 播放视频 / 图片滤镜编辑）

## 目的

当用户想让智能体操作相册媒体（例如「打开这张图片预览」「加黑白滤镜把这张图另存」）时，
本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.fde.gallery3d` 的
   `MediaAppFunctionsService` 暴露的 AppFunction：
   - `openImage`：打开图片预览，参数 `path`（必填）+ `title`（可选）；
   - `playVideo`：播放视频，参数 `path`（必填）+ `title`（可选）；
   - `editImage`：给图片添加滤镜并另存，参数 `path`（必填）+ `filter`（可选，缺省黑白）+
     `outputPath`（可选）。
3. 汇总结果，形成可读的中文输出。

## 功能与参数

### 1. 打开图片预览（openImage）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `path` | 是 | 图片绝对路径 |
| `title` | 否 | 显示标题，缺省取文件名 |

### 2. 播放视频（playVideo）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `path` | 是 | 视频绝对路径 |
| `title` | 否 | 显示标题 |

### 3. 图片滤镜编辑（editImage）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `path` | 是 | 源图片绝对路径 |
| `filter` | 否（缺省黑白） | 滤镜名称；未知滤镜设置侧返回失败 |
| `outputPath` | 否 | 输出路径（PNG）；缺省写入 Pictures/fde 目录并返回实际路径 |

`filter` 支持的取值（与 FdeGallery `MediaAppFunctionsService` 的滤镜表一致）：
`origin`/`original`（不作处理仅另存）、`soft`/`instafix`（软化）、`ansel`（黑白样式）、
`classic`/`testino`（经典）、`gorgeous`/`xpro`（华丽）、`retro`（复古）、
`black_white`/`bw`/`gray`（黑白）、`sepia`/`film`（怀旧）、`memory`/`cyano`（回忆）、
`yogurt`/`georgia`（优格）、`fleeting`/`sahara`（流年）、`shine`/`hdr`（发光）、
`mosaic`（马赛克）。

### 等效的 cmd 用法（app_function 命令行直调）

也可以通过 `app_function` 命令直接调用，效果与本 Skill 一致：

```shell
# 给 /sdcard/Download/bella.jpg 加黑白滤镜，另存为 bella2.jpg
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.fde.gallery3d \
  --function 'editImage' \
  --parameters '{"path":"/sdcard/Download/bella.jpg","filter":"black_white","outputPath":"/sdcard/Download/bella2.jpg"}'
```

## 行为约束

- 「打开图片」与「编辑图片」是互斥的两个操作：想打开预览就不要填 `filter`；
  想加滤镜另存就必须填 `filter`（例如把图片调成黑白的 `black_white`）。
- `editImage` 会采样加载源图（限制最大边长防 OOM）、按滤镜处理、以 PNG 写回
  `outputPath` 并触发相册扫描，处理在相册侧后台线程执行。
- 滤镜 `filter` 与 `outputPath` 均为字符串参数，由 AppFunctionDispatcher 直接下发。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户（而不是继续执行）。
- 本 Skill 只打开 / 播放 / 编辑相册媒体，不做其它本地文件操作。
