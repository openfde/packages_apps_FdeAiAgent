package com.fde.aiagent.appfunctions

import android.app.appfunctions.AppFunctionManager as PlatformAppFunctionManager
import android.app.appfunctions.ExecuteAppFunctionRequest as PlatformExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse as PlatformExecuteAppFunctionResponse
import android.app.appsearch.GenericDocument
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of executing an AppFunction. */
sealed interface AgentFunctionResult {
    /** Human readable description, safe to show in the UI / feed back to the model. */
    val displayText: String

    data class Success(override val displayText: String, val raw: String) : AgentFunctionResult
    data class Failure(override val displayText: String, val cause: Throwable? = null) : AgentFunctionResult
}

/**
 * Calls the media AppFunctions published by the on-device gallery (`com.fde.gallery3d`).
 *
 * This is the exact same capability as the shell command:
 * ```
 * cmd app_function execute-app-function \
 *   --package com.fde.gallery3d \
 *   --function 'com.fde.gallery3d.appfunctions.MediaAppFunctionsService#openImage' \
 *   --parameters '{"path": "/mnt/sdcard/Pictures/aa.png", "title": "aa"}'
 * ```
 * but performed in-process through the platform
 * `android.app.appfunctions.AppFunctionManager#executeAppFunction`.
 *
 * We deliberately use the **platform** API instead of the Jetpack
 * `androidx.appfunctions` wrapper: as a caller we only need to hand the request straight to the
 * system AppFunctions service, and the platform API avoids the app-local inventory/KSP pipeline
 * that the Jetpack wrapper requires.
 */
class GalleryAppFunctionsClient(private val context: Context) {

    companion object {
        /** Package that publishes the media AppFunctions. */
        const val GALLERY_PACKAGE = "com.fde.gallery3d"

        /** The service inside the gallery package that hosts the AppFunctions. */
        const val GALLERY_SERVICE = "com.fde.gallery3d.appfunctions.MediaAppFunctionsService"

        /** Function id of the "open an image" capability. */
        const val FUNC_OPEN_IMAGE = "openImage"

        /** Function id of the "play a video" capability. */
        const val FUNC_PLAY_VIDEO = "playVideo"

        /** AppFunctions need API 36 (Baklava) or a device that ships the extension library. */
        const val MIN_SDK_FOR_APP_FUNCTIONS = Build.VERSION_CODES.BAKLAVA

        /** Demo image path used when the user does not provide one. */
        const val DEFAULT_IMAGE_PATH = "/mnt/sdcard/Pictures/aa.png"

        /** Permission a *caller* needs to execute another app's AppFunctions. */
        const val PERMISSION_EXECUTE_APP_FUNCTIONS = "android.permission.EXECUTE_APP_FUNCTIONS"
    }

    /** True when the platform AppFunctions service exists on this device. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= MIN_SDK_FOR_APP_FUNCTIONS &&
            context.getSystemService(PlatformAppFunctionManager::class.java) != null

    /**
     * True when this install may actually execute **another** app's AppFunctions.
     *
     * `EXECUTE_APP_FUNCTIONS` is granted to privileged/system-signed apps (this project builds
     * with `privileged: true` + `certificate: "platform"` + `system_ext_specific: true`), so a
     * normal third-party install is denied at runtime by `AppFunctionManagerService`.
     */
    fun hasExecutePermission(): Boolean =
        context.checkSelfPermission(PERMISSION_EXECUTE_APP_FUNCTIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Executes `openImage` in [GALLERY_PACKAGE]. */
    suspend fun openImage(path: String, title: String): AgentFunctionResult =
        executeFunction(FUNC_OPEN_IMAGE, mapOf("path" to path, "title" to title))

    /** Executes `playVideo` in [GALLERY_PACKAGE]. */
    suspend fun playVideo(path: String, title: String): AgentFunctionResult =
        executeFunction(FUNC_PLAY_VIDEO, mapOf("path" to path, "title" to title))

    /**
     * Generic cross-app AppFunction execution.
     *
     * @param functionId the function identifier the gallery registers (e.g. `openImage`).
     * @param params     string properties written into the request's `GenericDocument` — the
     *                   gallery service reads them with `getPropertyString(key)`, so names must
     *                   match the gallery's declared schema.
     */
    suspend fun executeFunction(functionId: String, params: Map<String, String>): AgentFunctionResult {
        if (Build.VERSION.SDK_INT < MIN_SDK_FOR_APP_FUNCTIONS) {
            return AgentFunctionResult.Failure(
                "当前设备是 API ${Build.VERSION.SDK_INT}，AppFunctions 需要 API " +
                    "$MIN_SDK_FOR_APP_FUNCTIONS。",
            )
        }
        val manager = context.getSystemService(PlatformAppFunctionManager::class.java)
            ?: return AgentFunctionResult.Failure("本设备没有 android.app.appfunctions.AppFunctionManager。")

        if (!hasExecutePermission()) {
            return AgentFunctionResult.Failure(
                "缺少 $PERMISSION_EXECUTE_APP_FUNCTIONS（特权/系统签名权限）。" +
                    "FdeAiAgent 需要以 privileged + platform 签名安装才会被授予。",
            )
        }

        return try {
            val documentBuilder = GenericDocument.Builder<GenericDocument.Builder<*>>(
                /* namespace = */ GALLERY_PACKAGE,
                /* id = */ functionId,
                /* schemaType = */ functionId,
            )
            params.forEach { (key, value) -> documentBuilder.setPropertyString(key, value) }
            val parameters = documentBuilder.build()

            val request = PlatformExecuteAppFunctionRequest
                .Builder(GALLERY_PACKAGE, functionId)
                .setParameters(parameters)
                .build()

            val response = executePlatform(manager, request)
            // The gallery wraps its result document as `MediaResult`; on success it carries
            // `status="ok"`.
            val status = runCatching {
                response.resultDocument.getPropertyString("status")
            }.getOrNull()
            AgentFunctionResult.Success(
                displayText = "已调用 $GALLERY_PACKAGE#$functionId（$GALLERY_SERVICE）" +
                    (status?.let { "，返回 status=$it" } ?: "（返回文档无 status 字段）"),
                raw = status ?: "ok",
            )
        } catch (t: Throwable) {
            AgentFunctionResult.Failure(
                displayText = "AppFunctions 调用失败：${t.javaClass.simpleName}: ${t.message}",
                cause = t,
            )
        }
    }

    /** Bridges the platform `OutcomeReceiver` callback API into a suspend function. */
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
