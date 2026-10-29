---
name: file_find
version: 1.0.0
description: 在文件管理器 com.android.documentsui 中查找文件（DocumentsUI 的文件 AppFunction，只读）：按文件夹 + 名称/修改时间过滤条件查找文件（findFiles）、递归找出大文件（findLargeFiles）、递归找出 N 天未使用的文件（findUnusedFiles）
tags:
  - file
  - files
  - search
  - find
  - documents
  - appfunctions
functions:
  - findFiles
  - findLargeFiles
  - findUnusedFiles
inputs:
  find_folder: 要查找的文件夹路径（findFiles 模式必填）：绝对路径，如 /storage/emulated/0/Desktop/a.txt；也支持仅相对桌面的名称；只扫描其直接子项
  find_prefix: 名称前缀（可选）：仅返回名称以该前缀开头的子项
  find_suffix: 名称后缀（可选）：仅返回名称以该后缀结尾的子项
  find_last_months: 最近 N 个月内有修改（可选）：例如“最近三个月的文件”
  find_before_date: 修改时间早于该时间点（可选）：支持 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / epoch 毫秒
  find_after_date: 修改时间晚于该时间点（可选）：格式同上
  find_limit: 最多返回条数（可选，缺省不限；findLargeFiles / findUnusedFiles 缺省 50）
  large_path: 扫描大文件的文件夹路径（findLargeFiles 模式必填）：递归找出大文件
  min_size: 最小文件大小字节数（可选，缺省 104857600 即 100MB）
  unused_path: 扫描不常用文件的文件夹路径（findUnusedFiles 模式必填）：递归找出 N 天未访问/未修改的文件
  unused_days: 多少天（N）未访问/未修改视为不常用（可选，缺省 30）
  recursive: 是否递归子文件夹（可选，填 true / false，缺省 true）
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: find_files
    type: function
    function: findFiles
    description: 按过滤条件查找文件夹下的文件（find_folder 非空时执行）
    run_when: '$input.find_folder != ""'
    inputs:
      folder: "$input.find_folder"
      namePrefix: "$input.find_prefix"
      nameSuffix: "$input.find_suffix"
      lastMonths: "$input.find_last_months"
      beforeDate: "$input.find_before_date"
      afterDate: "$input.find_after_date"
      limit: "$input.find_limit"
  - id: find_large
    type: function
    function: findLargeFiles
    description: 递归找出文件夹下的大文件（large_path 非空时执行）
    run_when: '$input.large_path != ""'
    inputs:
      path: "$input.large_path"
      minSize: "$input.min_size"
      recursive: "$input.recursive"
      limit: "$input.find_limit"
  - id: find_unused
    type: function
    function: findUnusedFiles
    description: 递归找出 N 天未使用的不常用文件（unused_path 非空时执行）
    run_when: '$input.unused_path != ""'
    inputs:
      path: "$input.unused_path"
      days: "$input.unused_days"
      limit: "$input.find_limit"
  - id: report
    type: report
    template: "文件查找结果：按条件查找 => ${find_files.output}；找大文件 => ${find_large.output}；找不常用文件 => ${find_unused.output}"
---

# file_find — 查找文件（DocumentsUI 只读 AppFunction 查找）

## 目的

当用户想按条件找文件（例如「找桌面下所有 *.pdf」「找 500MB 以上的大文件」「找 30 天没用过的
文件」）时，本 Skill 通过 DocumentsUI 的 `DocumentsUiAppFunctionService` 暴露的只读
AppFunction 查找，**不会修改任何文件**：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 按填入的输入执行对应查找功能：
   - `findFiles`（填 `find_folder`）：按文件夹 + 名称/修改时间过滤条件只读查找**直接子项**；
   - `findLargeFiles`（填 `large_path`）：按 `min_size` 递归找出大文件，按大小降序；
   - `findUnusedFiles`（填 `unused_path`）：按 `unused_days` 递归找出最久未使用的不常用文件。
3. 汇总结果，形成可读的中文输出（含命中数量与路径列表）。

## 功能与参数

### 1. 按条件查找（findFiles）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `find_folder` | 是 | 要查找的文件夹路径，只扫描其**直接子项**（不递归） |
| `find_prefix` / `find_suffix` | 否 | 按名称前缀 / 后缀过滤 |
| `find_last_months` | 否 | 最近 N 个月内有修改（等价于 afterDate = “N 个月前”） |
| `find_before_date` / `find_after_date` | 否 | 修改时间早于 / 晚于某个时间点（yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / epoch 毫秒） |
| `find_limit` | 否 | 最多返回条数（按修改时间倒序取前 limit 条） |

结果按修改时间倒序返回，带 `count`（命中数）与 `matches`（路径列表）。

### 2. 找大文件（findLargeFiles）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `large_path` | 是 | 要递归扫描的文件夹路径 |
| `min_size` | 否（缺省 100MB） | 最小文件大小（字节），例如 524288000 表示 500MB |
| `recursive` | 否（缺省 true） | 是否递归子文件夹 |
| `find_limit` | 否（缺省 50） | 最多返回条数 |

结果带 `count`（命中总数，可能超过返回上限）、`paths` / `sizes` / `lastModified`。

### 3. 找不常用文件（findUnusedFiles）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `unused_path` | 是 | 要递归扫描的文件夹路径 |
| `unused_days` | 否（缺省 30） | N（天）内未被访问/修改视为不常用 |
| `find_limit` | 否（缺省 50） | 最多返回条数 |

结果按最久未使用在前排序，带 `count` / `paths` / `lastModified` / `lastAccessed`。

### 等效的 cmd 用法（app_function 命令行直调）

也可以通过 `app_function` 命令直接调用，效果与本 Skill 一致：

```shell
# 在 /storage/emulated/0/Desktop 下找所有 .pdf（按名称后缀过滤）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'findFiles' \
  --parameters '{"folder":"/storage/emulated/0/Desktop","nameSuffix":".pdf"}'

# 找 /mnt/sdcard 下超过 500MB 的大文件
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'findLargeFiles' \
  --parameters '{"path":"/mnt/sdcard","minSize":524288000}'

# 找 /mnt/sdcard/Downloads 里 30 天没用的文件
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'findUnusedFiles' \
  --parameters '{"path":"/mnt/sdcard/Downloads","days":30,"limit":20}'
```

## 行为约束

- 三个查找功能相互独立：填了哪个输入就执行对应的功能，未填写的步骤会被跳过（标记为
  SKIPPED），本 Skill 只读不写，不会删除/移动/修改任何文件。
- 路径参数支持绝对路径，也支持相对桌面（Desktop）的名称（如 `a.txt` 等价于
  `/storage/emulated/0/Desktop/a.txt`）。
- 目录不存在、时间参数解析失败等非法情况，DocumentsUI 侧通过 `AppFunctionException` 返回
  可读错误，分发层会反馈给用户（而不是继续执行）。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，并把具体
  原因返回给用户。
- 本 Skill 与 file_search Skill 的区别：file_search 是纯本地内置搜索（最多递归 3 层），
  本 Skill 走 DocumentsUI 的 AppFunction，支持更丰富的过滤条件（按修改时间 / 大文件 /
  未使用时间）并返回绝对路径。删除查到的文件请使用 file_delete Skill 或 file_op Skill。
