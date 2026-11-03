---
name: file_search
version: 1.0.0
description: 在设备指定目录中按后缀 / 关键字搜索文件，并返回前 N 个匹配结果
tags:
  - file
  - search
  - builtin
inputs:
  dir: 起始搜索目录（绝对路径，例如 /mnt/sdcard/Pictures）
  match: 匹配规则，例如 *.png、*.jpg 或包含的子串（默认 * 匹配全部）
  limit: 最多返回条数（默认 20）
input_required:
  - dir
steps:
  - id: check_dir
    type: check
    check: input
    check_inputs:
      - dir
    description: 校验搜索目录参数
  - id: scan
    type: builtin
    action: file_scan
    description: 扫描目录并统计匹配文件
    inputs:
      base_dir: "$input.dir"
      match: "$input.match"
      limit: "$input.limit"
  - id: report
    type: report
    template: "在 $input.dir 中按 '$input.match' 搜索完成：${scan.output}"
---

# file_search — 本地文件搜索

## 目的

当用户想找某个文件（图片 / 文档 / 压缩包等）时，本 Skill 通过内置的 `file_scan`
能力在指定目录（最多向下递归 3 层）中搜索，并按规则过滤、限量返回。

## 行为约束

- `dir` 是必填参数；目录不存在时，`scan` 步骤会被标记为失败。
- `match` 支持 `*.ext`（按后缀）或普通子串匹配，空则匹配全部。
- 本 Skill 只读不写，不涉及跨应用 AppFunctions，用于演示“纯本地 skill 步骤”。
