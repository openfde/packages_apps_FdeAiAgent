package com.fde.aiagent.appfunctions

/** 一个可被分发的 Android AppFunction（由某个已安装应用对外暴露）。 */
data class AppFunction(
    val id: String,
    val packageName: String,
    val serviceName: String,
    val description: String,
    val schemaJson: String,
    val requiredParams: List<String>,
)

/** AppFunction 调用结果（与模型 / UI 可交互的统一形态）。 */
sealed interface FunctionResult {
    /** 可读的中文描述，可直接展示给用户或反馈给模型。 */
    val displayText: String

    data class Success(override val displayText: String, val raw: String) : FunctionResult
    data class Failure(override val displayText: String, val cause: Throwable? = null) : FunctionResult
}
