---
name: file_op
version: 1.0.0
description: 文件管理操作（文件管理器 com.android.documentsui 的文件 AppFunction）：删除文件/文件夹（移入系统回收站，可恢复，deleteFile）、移动某个文件夹下的所有文件/子文件夹到目标目录（moveFiles）、重命名文件/文件夹（renameFolder）、清空系统回收站（永久删除，emptyTrash）
tags:
  - file
  - files
  - documents
  - appfunctions
functions:
  - deleteFile
  - moveFiles
  - renameFolder
  - emptyTrash
inputs:
  delete_path: 要删除（移入回收站）的文件/文件夹路径（删除时必填）：绝对路径，如 /storage/emulated/0/Desktop/a.txt；也支持仅相对桌面的名称，如 a.txt
  move_source: 源文件夹路径（移动时必填）：把该文件夹下的所有文件/子文件夹移动到目标目录；支持绝对路径或相对桌面的名称
  move_destination: 目标目录路径（移动时必填）：不存在时自动创建，目标中已有同名项会自动生成唯一名称避免覆盖
  rename_path: 要重命名的文件/文件夹路径（重命名时必填）：支持绝对路径或相对桌面的名称
  rename_new_name: 重命名后的新名称（重命名时必填，例如 b.txt，不能含路径分隔符）
  empty_trash: 是否清空系统回收站（清空时填 true，其它情况不填）
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: delete_file
    type: function
    function: deleteFile
    description: 删除文件/文件夹并移入系统回收站（delete_path 非空时执行）
    run_when: '$input.delete_path != ""'
    inputs:
      path: "$input.delete_path"
  - id: move_files
    type: function
    function: moveFiles
    description: 把源文件夹下的所有文件/子文件夹移动到目标目录（move_source 非空时执行）
    run_when: '$input.move_source != ""'
    inputs:
      sourcePath: "$input.move_source"
      destinationPath: "$input.move_destination"
  - id: rename_folder
    type: function
    function: renameFolder
    description: 重命名文件/文件夹（rename_new_name 非空时执行）
    run_when: '$input.rename_new_name != ""'
    inputs:
      path: "$input.rename_path"
      newName: "$input.rename_new_name"
  - id: empty_trash
    type: function
    function: emptyTrash
    description: 清空系统回收站（empty_trash 为 true 时执行）
    run_when: '$input.empty_trash == "true"'
  - id: report
    type: report
    template: "文件操作结果：删除 => ${delete_file.output}；移动 => ${move_files.output}；重命名 => ${rename_folder.output}；清空回收站 => ${empty_trash.output}"
---

# file_op — 文件管理操作（删除 / 移动 / 重命名 / 清空回收站）

## 目的

当用户想让智能体做文件管理操作（例如「把 a.txt 删除」「把 桌面/临时 里的文件都移到
桌面/归档」「把 报告.txt 改名为 报告2026.txt」「清空回收站」）时，本 Skill 负责：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 通过 AppFunctionDispatcher 跨应用调用 `com.android.documentsui` 的
   `DocumentsUiAppFunctionService` 暴露的 AppFunction：
   - `deleteFile`：删除（移入系统回收站）文件/文件夹，可在回收站页面恢复；
   - `moveFiles`：把源文件夹下的所有文件/子文件夹移动到某个目标目录；
   - `renameFolder`：重命名文件/文件夹；
   - `emptyTrash`：清空系统回收站，永久删除其中全部内容。
3. 汇总结果，形成可读的中文输出。

## 功能与参数

### 1. 删除（移入回收站）（deleteFile）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `delete_path` | 是 | 要删除的文件/文件夹路径，绝对路径或相对桌面的名称 |

与 DocumentsUI「删除即移入回收站」行为保持一致，删除后可在回收站页面恢复。

### 2. 移动文件（moveFiles）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `move_source` | 是 | 源文件夹路径 |
| `move_destination` | 是 | 目标目录路径，不存在时自动创建 |

仅移动源文件夹的子项本身（不合并子目录内容）；目标目录中已有同名项时自动生成唯一名称
（如 `a.txt_1.txt`）避免覆盖；返回实际移动成功的项数 `movedCount`。

### 3. 重命名（renameFolder）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `rename_path` | 是 | 要重命名的文件/文件夹路径 |
| `rename_new_name` | 是 | 新名称，不能含路径分隔符；目标位置已存在同名项则报错 |

对普通文件和文件夹都生效；成功返回新路径 `newPath`。

### 4. 清空回收站（emptyTrash）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `empty_trash` | 是 | 填 `true` |

永久删除回收站中的全部内容，**不可恢复**；返回实际删除的项数 `deletedCount`。
仅在用户明确要求「清空回收站」时使用。

### 等效的 cmd 用法（app_function 命令行直调）

也可以通过 `app_function` 命令直接调用，效果与本 Skill 一致：

```shell
# 删除 a.txt（移入回收站）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'deleteFile' \
  --parameters '{"path":"/storage/emulated/0/Desktop/a.txt"}'

# 把 桌面/临时 文件夹下的所有文件移动到 桌面/归档
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'moveFiles' \
  --parameters '{"sourcePath":"/storage/emulated/0/Desktop/临时","destinationPath":"/storage/emulated/0/Desktop/归档"}'

# 把 报告.txt 重命名为 报告2026.txt
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'renameFolder' \
  --parameters '{"path":"/storage/emulated/0/Desktop/报告.txt","newName":"报告2026.txt"}'

# 清空回收站
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'emptyTrash'
```

## 行为约束

- 四个操作相互独立：填了哪个参数就执行对应的功能，未填写或条件不满足的步骤会被跳过
  （标记为 SKIPPED）。移动需同时提供 `move_source` 与 `move_destination`，重命名需同时
  提供 `rename_path` 与 `rename_new_name`；缺参时设置侧返回
  `Missing required parameter` 错误，分发层会将其识别为失败并提示。
- `deleteFile` / `moveFiles` / `renameFolder` 的路径参数支持绝对路径，也支持相对桌面
  （Desktop）的名称（如 `a.txt` 等价于 `/storage/emulated/0/Desktop/a.txt`）。
- 目标路径不存在、文件不存在、目标已在源目录内等非法情况，设置侧通过
  `AppFunctionException` 返回可读错误，分发层会反馈给用户（而不是继续执行）。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，
  并把具体原因返回给用户。
- 本 Skill 只做文件管理（删除/移动/重命名/清空回收站），不做文件内容的读取/编写/搜索；
  文件搜索请使用 file_search Skill，图片/视频等媒体操作请使用 gallery Skill。
