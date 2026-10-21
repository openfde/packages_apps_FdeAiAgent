package com.fde.aiagent.ai

/**
 * 支持的 AI 供应商预设。
 *
 * - **OpenAI 兼容**（DeepSeek / 通义千问 / 智谱 GLM / Kimi / 自定义）：走标准的
 *   `chat/completions`，请求体与解析逻辑完全一致，只需切换 baseUrl 与默认模型；
 * - **Gemini（Google）**：REST 端点为 `generateContent`，函数声明（functionDeclarations）
 *   与返回的 functionCall 结构与 OpenAI 不同，由 [AiClient] 单独处理。
 *
 * 新增一个 OpenAI 兼容的供应商只需在枚举里加一项；想支持其它非兼容协议则需在 [AiClient] 中
 * 增加对应的请求/解析分支。
 */
enum class AiProvider(
    /** 唯一 id，持久化到 SharedPreferences。 */
    val id: String,
    /** 界面显示名。 */
    val label: String,
    /** OpenAI 兼容的 chat/completions 端点；Gemini 为 REST API base（不含模型名）。 */
    val apiUrl: String,
    /** 该供应商的默认模型，用户在界面上可覆盖。 */
    val defaultModel: String,
    /** true=OpenAI 兼容格式；false=其它格式（目前为 Gemini）。 */
    val openAiCompatible: Boolean,
) {
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek（深度求索）",
        apiUrl = "https://api.deepseek.com/chat/completions",
        defaultModel = "deepseek-chat",
        openAiCompatible = true,
    ),
    QWEN(
        id = "qwen",
        label = "通义千问（Qwen）",
        apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        defaultModel = "qwen-plus",
        openAiCompatible = true,
    ),
    ZHIPU(
        id = "zhipu",
        label = "智谱 AI（GLM）",
        apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        defaultModel = "glm-4-plus",
        openAiCompatible = true,
    ),
    KIMI(
        id = "kimi",
        label = "Kimi（月之暗面）",
        apiUrl = "https://api.moonshot.cn/v1/chat/completions",
        defaultModel = "moonshot-v1-8k",
        openAiCompatible = true,
    ),
    GEMINI(
        id = "gemini",
        label = "Google Gemini",
        apiUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-2.5-flash",
        openAiCompatible = false,
    ),
    /** 任意 OpenAI 兼容端点（baseUrl / 模型由用户在界面上填写）。 */
    CUSTOM(
        id = "custom",
        label = "自定义 OpenAI 兼容（Custom）",
        apiUrl = "",
        defaultModel = "gpt-4o",
        openAiCompatible = true,
    );

    companion object {
        fun fromId(id: String): AiProvider? = entries.firstOrNull { it.id == id }

        /** 在 [entries] 中的下标，用于恢复 Spinner 选中项。 */
        fun indexOf(id: String): Int = entries.indexOfFirst { it.id == id }
    }
}

/** 用户在界面上选择 / 填写后提交的 AI 配置（不含文件明文持久化的敏感信息也会被保存，仅本地）。 */
data class AiConfig(
    /** 供应商 id，见 [AiProvider.id]。 */
    val providerId: String = AiProvider.DEEPSEEK.id,
    /** 用户自己的 API Key（可留空，运行时会校验）。 */
    val apiKey: String = "",
    /** 自定义端点 / baseUrl（仅 [AiProvider.CUSTOM] 必填，其余可留空使用预设）。 */
    val baseUrl: String = "",
    /** 自定义模型名；留空使用 [AiProvider.defaultModel]。 */
    val model: String = "",
) {
    fun provider(): AiProvider = AiProvider.fromId(providerId) ?: AiProvider.DEEPSEEK

    /** 生效的请求端点：优先用户填写的 baseUrl，否则用供应商预设。 */
    fun resolvedBaseUrl(): String = baseUrl.trim().takeIf { it.isNotEmpty() } ?: provider().apiUrl

    /** 生效的模型名：优先用户填写的 model，否则用供应商默认。 */
    fun resolvedModel(): String = model.trim().takeIf { it.isNotEmpty() } ?: provider().defaultModel
}
