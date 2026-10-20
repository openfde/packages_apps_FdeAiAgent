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
            params.forEach { (key, value) -> documentBuilder.setPropertyString(key, value) }
            val parameters = documentBuilder.build()

            val request = PlatformExecuteAppFunctionRequest
                .Builder(fn.packageName, fn.id)
                .setParameters(parameters)
                .build()

            val response = executePlatform(manager, request)
            // 相册把结果文档包装为 MediaResult；成功时带 status="ok"。
            val status = runCatching {
                response.resultDocument.getPropertyString("status")
            }.getOrNull()

            FunctionResult.Success(
                displayText = "已调用 ${fn.packageName}#${fn.id}（${fn.serviceName}）" +
                    (status?.let { "，返回 status=$it" } ?: "（返回文档无 status 字段）"),
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
