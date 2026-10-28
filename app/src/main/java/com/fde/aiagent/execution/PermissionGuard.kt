package com.fde.aiagent.execution

import android.app.appfunctions.AppFunctionManager as PlatformAppFunctionManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.fde.aiagent.R

/**
 * 权限与执行层 —— 全应用唯一的安全把关点。
 *
 * 职责：
 *  - 判断平台是否具备 AppFunctions 能力（API >= 36 且存在 AppFunctionManager）；
 *  - 判断调用方是否持有 `EXECUTE_APP_FUNCTIONS`（特权/系统签名权限）；
 *  - 判断本地敏感写入是否需要 `CONTROL_SYSTEM`；
 *  - 提供统一的失败原因描述，供 SkillEngine / 直接调用 / 界面展示使用。
 *
 * 所有跨应用 AppFunction 调用都必须先经过 [checkAppFunctionExecution] 检查，
 * 由这里集中决定“能不能执行、为什么不能”。
 */
class PermissionGuard(private val context: Context) {

    companion object {
        /** 跨应用执行其他应用的 AppFunction 所需的调用方权限。 */
        const val PERMISSION_EXECUTE_APP_FUNCTIONS = "android.permission.EXECUTE_APP_FUNCTIONS"

        /** 本地敏感写入所需的系统级权限（FDE 平台私有权限，manifest 中已声明）。 */
        const val PERMISSION_CONTROL_SYSTEM = "com.fde.permission.CONTROL_SYSTEM"

        /** AppFunctions 平台 API 需要 API 36（Baklava）。 */
        const val MIN_SDK_FOR_APP_FUNCTIONS = Build.VERSION_CODES.BAKLAVA
    }

    private val appContext = context.applicationContext

    /** 平台 AppFunctionManager 服务，null 表示设备不支持。 */
    val appFunctionManager: PlatformAppFunctionManager?
        get() = if (Build.VERSION.SDK_INT >= MIN_SDK_FOR_APP_FUNCTIONS) {
            appContext.getSystemService(PlatformAppFunctionManager::class.java)
        } else null

    fun isAppFunctionsSupported(): Boolean = appFunctionManager != null

    fun hasExecuteAppFunctions(): Boolean =
        appContext.checkSelfPermission(PERMISSION_EXECUTE_APP_FUNCTIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 本地敏感写入（应用私有目录之外）是否被允许。 */
    fun allowLocalWrite(): Boolean =
        appContext.checkSelfPermission(PERMISSION_CONTROL_SYSTEM) == PackageManager.PERMISSION_GRANTED

    /**
     * 执行一个跨应用 AppFunction 前的集中检查。
     * @return null 表示可以执行；否则返回阻塞原因（可直接展示给用户/反馈给模型）。
     */
    fun checkAppFunctionExecution(): String? {
        if (Build.VERSION.SDK_INT < MIN_SDK_FOR_APP_FUNCTIONS) {
            return appContext.getString(
                R.string.guard_api_too_low,
                Build.VERSION.SDK_INT,
                MIN_SDK_FOR_APP_FUNCTIONS,
            )
        }
        if (appFunctionManager == null) {
            return appContext.getString(R.string.guard_no_manager)
        }
        if (!hasExecuteAppFunctions()) {
            return appContext.getString(R.string.guard_missing_permission, PERMISSION_EXECUTE_APP_FUNCTIONS)
        }
        return null
    }

    /** 当前权限状态的摘要（调试/提示用）。 */
    fun describe(): String {
        val granted = appContext.getString(R.string.describe_granted)
        val notGranted = appContext.getString(R.string.describe_not_granted)
        val sb = StringBuilder()
        sb.append(
            appContext.getString(
                R.string.describe_supported,
                if (isAppFunctionsSupported()) "yes" else "no",
            ),
        ).append('\n')
        sb.append("EXECUTE_APP_FUNCTIONS: ")
            .append(if (hasExecuteAppFunctions()) granted else notGranted).append('\n')
        sb.append(
            appContext.getString(
                R.string.describe_local_write,
                if (allowLocalWrite()) granted else appContext.getString(R.string.describe_local_write_partial),
            ),
        ).append('\n')
        if (!hasExecuteAppFunctions()) {
            sb.append(appContext.getString(R.string.describe_privileged_hint))
        }
        return sb.toString()
    }
}
