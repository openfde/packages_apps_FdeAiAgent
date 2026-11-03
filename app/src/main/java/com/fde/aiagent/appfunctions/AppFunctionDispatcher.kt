package com.fde.aiagent.appfunctions

import android.app.appfunctions.AppFunctionManager as PlatformAppFunctionManager
import android.app.appfunctions.ExecuteAppFunctionRequest as PlatformExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse as PlatformExecuteAppFunctionResponse
import android.app.appsearch.GenericDocument
import android.content.Context
import com.fde.aiagent.R
import com.fde.aiagent.execution.PermissionGuard
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * AppFunction 分发器 —— 真正发起平台级跨应用调用。
 *
 * 每一个分发动作都先经过 [PermissionGuard] 的集中检查，再通过平台
 * `android.app.appfunctions.AppFunctionManager#executeAppFunction` 执行，等价于：
 * ```
 * cmd app_function execute-app-function \
 *   --package <pkg> --function '<service>#<functionId>' --parameters '{...}'
 * ```
 *
 * 我们使用**平台 API**（而非 Jetpack androidx.appfunctions 包装），因为在 Binder 上
 * 我们只需要把请求直通给系统 AppFunction 服务即可，避开 app-local 的库存/KSP 管线。
 */
class AppFunctionDispatcher(
    private val context: Context,
    private val guard: PermissionGuard,
) {

    /**
     * 分发一个 [fn] 调用。返回 [FunctionResult.Success] / [FunctionResult.Failure]，
     * 失败时 [FunctionResult.Failure.displayText] 已是可读中文原因（含权限/平台原因）。
     */
    suspend fun dispatch(fn: AppFunction, params: Map<String, String>): FunctionResult {
        guard.checkAppFunctionExecution()?.let { issue ->
            return FunctionResult.Failure(issue)
        }
        return doExecute(fn, params)
    }

    private suspend fun doExecute(fn: AppFunction, params: Map<String, String>): FunctionResult {
        val manager = guard.appFunctionManager
            ?: return FunctionResult.Failure(context.getString(R.string.fn_manager_unavailable))

        return try {
            val documentBuilder = GenericDocument.Builder<GenericDocument.Builder<*>>(
                /* namespace = */ fn.packageName,
                /* id = */ fn.id,
                /* schemaType = */ fn.id,
            )
            // 数字参数（如 adjustBrightness 的 step）需要以 Long 属性下发，Settings 侧通过
            // getPropertyLongArray 读取；纯字符串参数保持 setPropertyString。
            params.forEach { (key, value) ->
                val longValue = value.trim().toLongOrNull()
                if (longValue != null) {
                    documentBuilder.setPropertyLong(key, longValue)
                } else {
                    documentBuilder.setPropertyString(key, value)
                }
            }
            val parameters = documentBuilder.build()

            val request = PlatformExecuteAppFunctionRequest
                .Builder(fn.packageName, fn.id)
                .setParameters(parameters)
                .build()

            val response = executePlatform(manager, request)
            // 不同应用的结果文档形态不同：
            //  - 相册 MediaResult：成功带 status="ok"；
            //  - 设置 SettingsPageResult：成功 ok=true、status="launch_requested"；
            //    失败 ok=false、error="unsupported_page" 等；
            //  - 设置 BrightnessAdjustResult：成功 ok=true、status="brightness_adjusted"、
            //    带 action/before/after；失败 ok=false、error="invalid_action" 等；
            //  - 设置 MediaVolumeResult：成功 status="media_volume_adjusted"，带 before/after
            //    （百分比）；失败 ok=false、error="invalid_action" 等；
            //  - 设置 DarkThemeResult：成功 status="dark_theme_set"，带 action/enabled；
            //  - 设置 OpenAppResult：成功 status="launch_requested"，带解析出的 packageName/
            //    appName；
            //  - DocumentsUI FileOpResult：成功 status="ok"，deleteFile 带 path、moveFiles 带
            //    movedCount（Long）、renameFolder 带 path/newPath、emptyTrash 带 deletedCount（Long）；
            //    失败通过 AppFunctionException（onError）返回。
            //  - FDE 精选应用商店（com.fde.download）DownloadAndInstallResponse：带 submitted
            //    （Boolean）、status（submitted / already_installed）、appName / packageName /
            //    version / message；GetAvailableAppsResponse：带 apps（对象数组，每项含
            //    name / packageName / version / installed / updateAvailable）与 isTruncated。
            // 这里统一解析，把设置侧的业务失败也归为 Failure 反馈给用户/模型。
            val doc = response.resultDocument
            val names = runCatching { doc.propertyNames }.getOrNull() ?: emptySet()
            val status = runCatching { doc.getPropertyString("status") }.getOrNull()
            // 结果文档可能携带业务输出（如 editImage 的 outputPath），一并展示给用户。
            val outputPath = runCatching { doc.getPropertyString("outputPath") }.getOrNull()
            // 调节型结果（亮度/音量）带调整前后百分比（Long）。
            val before = runCatching { doc.getPropertyLong("before") }.getOrNull()
            val after = runCatching { doc.getPropertyLong("after") }.getOrNull()
            // 深色主题结果带最终开关状态（getPropertyBoolean 在属性缺失时返回 false，需先判存在）。
            val enabled = if (names.contains("enabled")) {
                runCatching { doc.getPropertyBoolean("enabled") }.getOrNull()
            } else {
                null
            }
            // 媒体音量结果带调节后的静音状态（mute/unmute 后回传 muted 布尔）。
            val muted = if (names.contains("muted")) {
                runCatching { doc.getPropertyBoolean("muted") }.getOrNull()
            } else {
                null
            }
            // openApp 结果带解析后的包名 / 应用名。
            val resolvedPackage = runCatching { doc.getPropertyString("packageName") }.getOrNull()
            val resolvedAppName = runCatching { doc.getPropertyString("appName") }.getOrNull()
            // DocumentsUI 文件操作结果：deleteFile/renameFolder 带 path（与 newPath）；
            // moveFiles / emptyTrash 带数量统计（Long）。
            val resultPath = runCatching { doc.getPropertyString("path") }.getOrNull()
            val newPath = runCatching { doc.getPropertyString("newPath") }.getOrNull()
            val movedCount = runCatching { doc.getPropertyLong("movedCount") }.getOrNull()
            val deletedCount = runCatching { doc.getPropertyLong("deletedCount") }.getOrNull()
            // 查找/文件列表结果：findFiles 带 count + matches；findLargeFiles / findUnusedFiles 带
            // count + paths；批量删除结果带 deletedCount + paths（最多 200 条，展示时截断前 3 条）。
            val count = runCatching { doc.getPropertyLong("count") }.getOrNull()
            val matches = runCatching { doc.getPropertyStringArray("matches") }.getOrNull()
            val resultPaths = runCatching { doc.getPropertyStringArray("paths") }.getOrNull()
            val fileList = listOf(
                matches?.toList() ?: emptyList(),
                resultPaths?.toList() ?: emptyList(),
            ).flatten().filter { it.isNotBlank() }.distinct().take(3).joinToString("；")
            // 批量删除是否被用户在确认弹窗中取消。
            val cancelled = if (names.contains("cancelled")) {
                runCatching { doc.getPropertyBoolean("cancelled") }.getOrNull()
            } else {
                null
            }
            // FDE 精选应用商店：downloadAndInstallApp 是否已提交后台下载 + 静默安装
            // （submitted）；已是最新版本时 submitted=false、status="already_installed"。
            val submitted = if (names.contains("submitted")) {
                runCatching { doc.getPropertyBoolean("submitted") }.getOrNull()
            } else {
                null
            }
            // DownloadAndInstallResponse 的 appName / packageName / version / message。
            val resultMessage = runCatching { doc.getPropertyString("message") }.getOrNull()
            val resultVersion = runCatching { doc.getPropertyString("version") }.getOrNull()
            // getAvailableApps 的 apps 对象数组（每项是 AvailableApp 的 GenericDocument）。
            val apps = runCatching { doc.getPropertyDocumentArray("apps") }.getOrNull()
            val isTruncated = if (names.contains("isTruncated")) {
                runCatching { doc.getPropertyBoolean("isTruncated") }.getOrNull()
            } else {
                null
            }
            // getPropertyBoolean 在属性缺失时返回 false，必须先判断属性是否存在。
            val hasOkProp = names.contains("ok")
            val ok = if (hasOkProp) runCatching { doc.getPropertyBoolean("ok") }.getOrNull() else null
            val error = runCatching { doc.getPropertyString("error") }.getOrNull()

            if (ok == false || !error.isNullOrEmpty()) {
                return FunctionResult.Failure(
                    displayText = context.getString(
                        R.string.fn_failed_text,
                        fn.id,
                        fn.packageName,
                        (error?.takeIf { it.isNotEmpty() }
                            ?: context.getString(R.string.fn_unknown_error)) +
                            (status?.let { context.getString(R.string.fn_status_suffix, it) } ?: ""),
                    ),
                )
            }

            FunctionResult.Success(
                displayText = buildString {
                    append(context.getString(R.string.fn_invoked_text, fn.packageName, fn.id, fn.serviceName))
                    if (status != null) append(context.getString(R.string.fn_status_returned, status))
                    else append(context.getString(R.string.fn_no_status))
                    if (before != null && after != null) {
                        append(context.getString(R.string.fn_before_after, before, after))
                    }
                    if (enabled != null) {
                        append(context.getString(if (enabled) R.string.fn_dark_on else R.string.fn_dark_off))
                    }
                    if (muted != null) {
                        append(context.getString(if (muted) R.string.fn_muted_yes else R.string.fn_muted_no))
                    }
                    if (resolvedPackage != null) {
                        append(context.getString(R.string.fn_resolved_package, resolvedPackage))
                    }
                    if (resolvedAppName != null) {
                        append(context.getString(R.string.fn_resolved_app, resolvedAppName))
                    }
                    if (resultPath != null) {
                        append(context.getString(R.string.fn_result_path, resultPath))
                    }
                    if (newPath != null) {
                        append(context.getString(R.string.fn_new_path, newPath))
                    }
                    if (movedCount != null) {
                        append(context.getString(R.string.fn_moved_count, movedCount))
                    }
                    if (deletedCount != null) {
                        append(context.getString(R.string.fn_deleted_count, deletedCount))
                    }
                    if (count != null) {
                        append(context.getString(R.string.fn_find_count, count))
                    }
                    if (fileList.isNotEmpty()) {
                        append(context.getString(R.string.fn_paths, fileList))
                    }
                    if (cancelled == true) {
                        append(context.getString(R.string.fn_cancelled_yes))
                    }
                    if (submitted != null) {
                        append(
                            context.getString(
                                if (submitted) R.string.fn_submitted_yes else R.string.fn_submitted_no,
                            ),
                        )
                    }
                    resultVersion?.takeIf { it.isNotEmpty() }?.let {
                        append(context.getString(R.string.fn_result_version, it))
                    }
                    if (apps != null && apps.isNotEmpty()) {
                        append(
                            context.getString(
                                R.string.fn_curated_apps,
                                apps.size,
                                formatCuratedApps(apps),
                            ),
                        )
                        if (isTruncated == true) {
                            append(context.getString(R.string.fn_curated_apps_truncated))
                        }
                    }
                    resultMessage?.takeIf { it.isNotEmpty() }?.let {
                        append(context.getString(R.string.fn_result_message, it))
                    }
                    outputPath?.takeIf { it.isNotEmpty() }?.let { append(context.getString(R.string.fn_output_path, it)) }
                },
                raw = status ?: "ok",
            )
        } catch (t: Throwable) {
            FunctionResult.Failure(
                displayText = context.getString(R.string.fn_call_failed, "${t.javaClass.simpleName}: ${t.message}"),
                cause = t,
            )
        }
    }

    /**
     * 把 getAvailableApps 返回的 AvailableApp 文档数组格式化为可读列表（最多截取 12 项）。
     * 每项显示为：名称(包名) v版本 状态标记，如 `微信(com.tencent.mm) v8.0.1 可更新`。
     */
    private fun formatCuratedApps(apps: Array<GenericDocument>): String {
        val shown = apps.take(12).joinToString("；") { formatCuratedApp(it) }
        return if (apps.size > 12) "$shown …" else shown
    }

    private fun formatCuratedApp(app: GenericDocument): String {
        val name = runCatching { app.getPropertyString("name") }.getOrNull().orEmpty()
        val pkg = runCatching { app.getPropertyString("packageName") }.getOrNull().orEmpty()
        val version = runCatching { app.getPropertyString("version") }.getOrNull().orEmpty()
        val propNames = runCatching { app.propertyNames }.getOrNull() ?: emptySet()
        val installed = propNames.contains("installed") &&
            runCatching { app.getPropertyBoolean("installed") }.getOrDefault(false)
        val update = propNames.contains("updateAvailable") &&
            runCatching { app.getPropertyBoolean("updateAvailable") }.getOrDefault(false)
        val state = when {
            update -> context.getString(R.string.fn_app_state_update)
            installed -> context.getString(R.string.fn_app_state_installed)
            else -> context.getString(R.string.fn_app_state_not_installed)
        }
        return buildString {
            append(if (name.isNotEmpty()) name else pkg.ifEmpty { "?" })
            if (pkg.isNotEmpty() && pkg != name) append("($pkg)")
            if (version.isNotEmpty()) append(" v$version")
            append(state)
        }
    }

    /** 把平台 OutcomeReceiver 回调桥接为 suspend 函数。 */
    private suspend fun executePlatform(
        manager: PlatformAppFunctionManager,
        request: PlatformExecuteAppFunctionRequest,
    ): PlatformExecuteAppFunctionResponse = suspendCancellableCoroutine { continuation ->
        manager.executeAppFunction(
            request,
            Runnable::run,
            android.os.CancellationSignal(),
            object :
                android.os.OutcomeReceiver<
                    PlatformExecuteAppFunctionResponse,
                    android.app.appfunctions.AppFunctionException,
                    > {
                override fun onResult(result: PlatformExecuteAppFunctionResponse) {
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onError(error: android.app.appfunctions.AppFunctionException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            },
        )
    }
}
