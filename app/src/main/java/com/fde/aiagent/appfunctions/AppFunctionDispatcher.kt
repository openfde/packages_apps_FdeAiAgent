package com.fde.aiagent.appfunctions

import android.app.appfunctions.AppFunctionManager as PlatformAppFunctionManager
import android.app.appfunctions.ExecuteAppFunctionRequest as PlatformExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse as PlatformExecuteAppFunctionResponse
import android.app.appsearch.GenericDocument
import android.content.Context
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
        val manager = guard.appFunctionManager ?: return FunctionResult.Failure("AppFunctionManager 不可用。")

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
            //    appName。
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
            // openApp 结果带解析后的包名 / 应用名。
            val resolvedPackage = runCatching { doc.getPropertyString("packageName") }.getOrNull()
            val resolvedAppName = runCatching { doc.getPropertyString("appName") }.getOrNull()
            // getPropertyBoolean 在属性缺失时返回 false，必须先判断属性是否存在。
            val hasOkProp = names.contains("ok")
            val ok = if (hasOkProp) runCatching { doc.getPropertyBoolean("ok") }.getOrNull() else null
            val error = runCatching { doc.getPropertyString("error") }.getOrNull()

            if (ok == false || !error.isNullOrEmpty()) {
                return FunctionResult.Failure(
                    displayText = "AppFunction ${fn.id}（${fn.packageName}）返回失败：" +
                        (error?.takeIf { it.isNotEmpty() } ?: "未知错误") +
                        (status?.let { "，status=$it" } ?: ""),
                )
            }

            FunctionResult.Success(
                displayText = buildString {
                    append("已调用 ${fn.packageName}#${fn.id}（${fn.serviceName}）")
                    if (status != null) append("，返回 status=$status") else append("（返回文档无 status 字段）")
                    if (before != null && after != null) append("，调整前 $before% → 调整后 $after%")
                    if (enabled != null) append("，深色模式=${if (enabled) "开启" else "关闭"}")
                    if (resolvedPackage != null) append("，应用包名=$resolvedPackage")
                    if (resolvedAppName != null) append("，应用名=$resolvedAppName")
                    outputPath?.takeIf { it.isNotEmpty() }?.let { append("，输出: $it") }
                },
                raw = status ?: "ok",
            )
        } catch (t: Throwable) {
            FunctionResult.Failure(
                displayText = "AppFunctions 调用失败：${t.javaClass.simpleName}: ${t.message}",
                cause = t,
            )
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
