package com.fde.aiagent.execution

import android.app.appfunctions.AppFunctionManager as PlatformAppFunctionManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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
            return "当前设备是 API ${Build.VERSION.SDK_INT}，AppFunctions 需要 API " +
                "$MIN_SDK_FOR_APP_FUNCTIONS。"
        }
        if (appFunctionManager == null) {
            return "本设备没有 android.app.appfunctions.AppFunctionManager。"
        }
        if (!hasExecuteAppFunctions()) {
            return "缺少 $PERMISSION_EXECUTE_APP_FUNCTIONS（特权/系统签名权限）。" +
                "FdeAiAgent 需要以 privileged + platform 签名装入 system_ext 才会被授予该权限。"
        }
        return null
    }

    /** 供「权限说明」按钮使用的摘要。 */
    fun describe(): String {
        val sb = StringBuilder()
        sb.append("AppFunctions 支持: ").append(isAppFunctionsSupported()).append('\n')
        sb.append("EXECUTE_APP_FUNCTIONS: ")
            .append(if (hasExecuteAppFunctions()) "已授予" else "未授予").append('\n')
        sb.append("本地写入(CONTROL_SYSTEM): ")
            .append(if (allowLocalWrite()) "已授予" else "未授予（仅应用私有目录可写）").append('\n')
        if (!hasExecuteAppFunctions()) {
            sb.append("提示：本应用需以 privileged + platform 签名装入 system_ext 才会被授予 EXECUTE_APP_FUNCTIONS。")
        }
        return sb.toString()
    }
}
