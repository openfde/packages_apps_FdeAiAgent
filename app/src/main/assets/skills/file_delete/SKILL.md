---
name: file_delete
version: 1.0.0
description: 批量删除文件（移入系统回收站，可恢复，DocumentsUI 的文件 AppFunction）：按名称前缀/后缀删除文件夹下的文件（deleteFilesByName）、按最后修改时间 before/after 删除（deleteFilesByTime）、删除显式列出的路径（deleteFiles）。支持 requireConfirmation 逐项弹窗确认（缺省 true）
tags:
  - file
  - files
  - delete
  - documents
  - appfunctions
functions:
  - deleteFilesByName
  - deleteFilesByTime
  - deleteFiles
inputs:
  name_path: 按名称删除的文件夹路径（deleteFilesByName 模式必填）：删除其中以 prefix 开头 / suffix 结尾的所有文件
  prefix: 名称前缀（可选，与 suffix 至少提供一个）：删除以该前缀开头的文件
  suffix: 名称后缀（可选，与 prefix 至少提供一个）：删除以该后缀结尾的文件
  time_path: 按时间删除的文件夹路径（deleteFilesByTime 模式必填）：删除其中最后修改时间满足 before / after 的文件
  before: 最后修改时间早于该时间戳（可选，Unix epoch 毫秒，与 after 至少提供一个）
  after: 最后修改时间晚于该时间戳（可选，Unix epoch 毫秒，与 before 至少提供一个）
  delete_path: 显式删除的文件/文件夹路径（deleteFiles 模式必填）：移入回收站
  recursive: 是否递归子文件夹（可选，填 true / false，缺省 true）
  require_confirmation: 是否删除前弹窗逐项确认（可选，缺省 true；填 false 直接删除不弹窗）
steps:
  - id: check_platform
    type: check
    check: appfunctions
    description: 检查 AppFunctionManager 与 EXECUTE_APP_FUNCTIONS 权限
  - id: delete_by_name
    type: function
    function: deleteFilesByName
    description: 按名称前缀/后缀删除文件夹下的文件（name_path 非空时执行）
    run_when: '$input.name_path != ""'
    inputs:
      path: "$input.name_path"
      prefix: "$input.prefix"
      suffix: "$input.suffix"
      recursive: "$input.recursive"
      requireConfirmation: "$input.require_confirmation"
  - id: delete_by_time
    type: function
    function: deleteFilesByTime
    description: 按最后修改时间删除文件（time_path 非空时执行；before/after 为 epoch 毫秒）
    run_when: '$input.time_path != ""'
    inputs:
      path: "$input.time_path"
      before: "$input.before"
      after: "$input.after"
      recursive: "$input.recursive"
      requireConfirmation: "$input.require_confirmation"
  - id: delete_files
    type: function
    function: deleteFiles
    description: 删除显式列出的文件路径（delete_path 非空时执行）
    run_when: '$input.delete_path != ""'
    inputs:
      paths: "$input.delete_path"
      requireConfirmation: "$input.require_confirmation"
  - id: report
    type: report
    template: "批量删除结果：按名称删除 => ${delete_by_name.output}；按时间删除 => ${delete_by_time.output}；删除列表 => ${delete_files.output}"
---

# file_delete — 批量删除文件（DocumentsUI：按名称 / 按时间 / 删除列表）

## 目的

当用户想让智能体批量删除文件（例如「把 桌面/临时 下所有 .tmp 删掉」「删掉 2026 年 1 月 1 号之前
修改的文件」）时，本 Skill 通过 DocumentsUI 的 `DocumentsUiAppFunctionService` 暴露的
AppFunction 删除，与「删除即移入回收站」行为保持一致，**删除后可在回收站页面恢复**：

1. 先通过权限守卫检查平台 AppFunctionManager 是否可用、调用方是否持有
   `android.permission.EXECUTE_APP_FUNCTIONS`；
2. 按填入的输入执行对应删除功能：
   - `deleteFilesByName`（填 `name_path`）：删除该文件夹下以 `prefix` 开头 / `suffix` 结尾
     的所有文件（`prefix` 与 `suffix` 至少提供一个，都提供时需同时满足）；
   - `deleteFilesByTime`（填 `time_path`）：删除该文件夹下最后修改时间早于 `before` /
     晚于 `after`（Epoch 毫秒）的文件（二者至少提供一个）；
   - `deleteFiles`（填 `delete_path`）：删除显式列出的文件路径。
3. 汇总结果，形成可读的中文输出（含实际删除数量；弹窗确认被取消时也会如实反馈）。

## 功能与参数

### 1. 按名称删除（deleteFilesByName）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `name_path` | 是 | 要扫描删除的文件夹路径 |
| `prefix` / `suffix` | 至少一个 | 删除名称以该前缀开头 / 后缀结尾的文件 |
| `recursive` | 否（缺省 true） | 是否递归子文件夹 |
| `require_confirmation` | 否（缺省 true） | 删除前弹窗逐项确认；传 false 直接删除 |

只删除匹配的普通文件，不影响文件夹本身；返回 `deletedCount` 与（最多 200 条的）`paths`。

### 2. 按时间删除（deleteFilesByTime）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `time_path` | 是 | 要扫描删除的文件夹路径 |
| `before` / `after` | 至少一个 | 最后修改时间早于 / 晚于该时间戳（Unix epoch 毫秒）；都提供时需同时满足 |
| `recursive` | 否（缺省 true） | 是否递归子文件夹 |
| `require_confirmation` | 否（缺省 true） | 删除前弹窗逐项确认；传 false 直接删除 |

返回 `deletedCount`（实际移入回收站的数量）。

### 3. 删除列表（deleteFiles）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `delete_path` | 是 | 显式列出的文件/文件夹路径（单个路径），移入回收站 |
| `require_confirmation` | 否（缺省 true） | 删除前弹窗逐项确认；传 false 直接删除 |

与 `deleteFile`（file_op Skill）行为一致，返回 `deletedCount` 与 `paths`；适合「把刚才找到的文件
删掉」这类需要显式指定目标的场景（本版本以单个路径下发）。

### 等效的 cmd 用法（app_function 命令行直调）

也可以通过 `app_function` 命令直接调用，效果与本 Skill 一致：

```shell
# 删除 桌面/临时 下所有 .tmp 文件（递归，需确认时缺省弹窗；这里传 false 直接删除）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'deleteFilesByName' \
  --parameters '{"path":"/storage/emulated/0/Desktop/临时","suffix":".tmp","recursive":"true","requireConfirmation":"false"}'

# 删除 桌面/old 里 2026-01-01 之前修改的文件（epoch 毫秒，需确认时缺省弹窗）
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'deleteFilesByTime' \
  --parameters '{"path":"/storage/emulated/0/Desktop/old","before":1767196800000,"recursive":"true"}'

# 删除显式指定的文件
cmd app_function execute-app-function \
  --brief-yaml \
  --package com.android.documentsui \
  --function 'deleteFiles' \
  --parameters '{"paths":"/mnt/sdcard/Download/a.png"}'
```

## 行为约束

- 三个删除功能相互独立：填了哪个输入就执行对应的功能，未填写的步骤会被跳过（标记为
  SKIPPED）。
- **删除是破坏性操作（虽然可恢复）**：`deleteFilesByName` 要求至少提供一个名称条件、
  `deleteFilesByTime` 要求至少提供一个时间条件，避免误删整个文件夹；`require_confirmation`
  缺省 true，删除前会弹出对话框列出待删除文件、用户可取消勾选并确认后才移入回收站，
  用户取消时返回 `cancelled=true`。
- 路径参数支持绝对路径（如 `/storage/emulated/0/Desktop/a.txt`），也支持相对桌面（Desktop）
  的名称（如 `a.txt` 等价于 `/storage/emulated/0/Desktop/a.txt`）。
- 目标路径不存在、文件不存在、参数缺失等非法情况，DocumentsUI 侧通过 `AppFunctionException`
  返回可读错误，分发层会反馈给用户（而不是继续执行）。
- 每个 AppFunction 步骤都会先经过 `PermissionGuard`，缺少权限时该节点标记为失败，并把具体
  原因返回给用户。
- 查找要删的文件请先使用 file_find / file_search Skill；单文件删除请使用 file_op Skill
  （deleteFile）。本 Skill 只做删除，不做文件的读取/移动/重命名。
