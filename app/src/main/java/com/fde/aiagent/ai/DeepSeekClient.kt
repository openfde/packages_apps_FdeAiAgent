package com.fde.aiagent.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/** 供 DeepSeek 规划使用的 Skill 摘要（避免 ai 层直接依赖 skill 层的完整模型）。 */
data class SkillBrief(
    val id: String,
    val description: String,
    val inputSpec: String,
)

/** DeepSeek 规划结果：要么选择某个 Skill 并填好参数，要么直接回答。 */
sealed interface ModelPlan {
    data class RunSkill(val skillId: String, val inputs: Map<String, String>) : ModelPlan
    data class Answer(val text: String) : ModelPlan
}

/**
 * DeepSeek 客户端 —— 负责「理解用户意图 & 规划任务」。
 *
 * 只做两件事：
 *  1. 维持与 DeepSeek chat-completions 的 HTTP 会话（OkHttp + Gson，均已在树内）；
 *  2. 把用户 Prompt + 已注册 Skill 目录喂给模型，让它通过 `run_skill` 工具选择一个
 *     Skill 并填好入参；模型若不调用工具则直接回答。
 *
 * 它**不直接调用 AppFunction / 内置工具** —— 具体执行交给 SkillEngine，
 * 从而把「做什么（规划）」与「怎么做（执行）」彻底解耦。
 */
class DeepSeekClient(
    private val apiKey: String,
    private val log: (String) -> Unit = {},
) {

    companion object {
        /** DeepSeek OpenAI 兼容的 chat completions 端点。 */
        const val API_URL = "https://api.deepseek.com/chat/completions"

        /** DeepSeek 通用模型。 */
        const val DEFAULT_MODEL = "deepseek-chat"

        /** 规划阶段暴露给模型的唯一工具。 */
        const val TOOL_RUN_SKILL = "run_skill"

        private val JSON = MediaType.parse("application/json; charset=utf-8")!!
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 规划入口：给定用户 [prompt] 与可用 [skills]，返回 [ModelPlan.RunSkill] 或 [ModelPlan.Answer]。
     */
    suspend fun plan(prompt: String, skills: List<SkillBrief>): ModelPlan {
        if (apiKey.isBlank()) {
            throw IllegalStateException("未配置 DeepSeek API Key，请先在界面上填写并保存。")
        }

        val messages = arrayListOf(systemMessage(skills), message("user", prompt))
        val toolDef = runSkillTool(skills)

        log("→ POST $API_URL (model=$DEFAULT_MODEL, skills=${skills.size})")
        val completion = complete(messages, listOf(toolDef))
        val choice = completion.getAsJsonArray("choices")?.asSequence()?.firstOrNull()?.asJsonObject
            ?: throw IllegalStateException("DeepSeek 没有返回任何 choice。")

        val msg = choice.getAsJsonObject("message")
        val content = msg.get("content")?.takeUnless { it.isJsonNull }?.asString
        val toolCalls = msg.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray

        if (toolCalls == null || toolCalls.size() == 0) {
            log("← 模型直接回答（不调用 Skill）")
            return ModelPlan.Answer(content ?: "")
        }

        for (call in toolCalls) {
            val fn = call.asJsonObject.getAsJsonObject("function")
            val name = fn.get("name")?.asString ?: ""
            if (name == TOOL_RUN_SKILL) {
                val argsRaw = fn.get("arguments")?.takeUnless { it.isJsonNull }?.asString ?: "{}"
                val args = runCatching { JsonParser.parseString(argsRaw).asJsonObject }.getOrNull()
                    ?: JsonObject()

                val skill = (args.get("skill")?.takeUnless { it.isJsonNull }?.asString ?: "").trim()
                if (skill.isEmpty()) {
                    throw IllegalStateException("模型返回的 run_skill 缺少 skill 参数。")
                }

                val inputs = LinkedHashMap<String, String>()
                args.get("inputs")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                    ?.forEach { (k, v) ->
                        if (!v.isJsonNull) {
                            inputs[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                        }
                    }
                log("← 模型规划: skill=$skill inputs=$inputs")
                return ModelPlan.RunSkill(skill, inputs)
            }
        }

        return ModelPlan.Answer(content ?: "模型请求了未知工具，请重试。")
    }

    /** 阻塞式 chat-completions 请求，须在 IO 调度器调用。 */
    private suspend fun complete(
        messages: List<JsonObject>,
        tools: List<JsonObject>,
    ): JsonObject = withContext(Dispatchers.IO) {
        val payload = JsonObject()
        payload.addProperty("model", DEFAULT_MODEL)
        payload.addProperty("temperature", 0.0)

        val msgArray = JsonArray()
        messages.forEach { msgArray.add(it.deepCopy()) }
        payload.add("messages", msgArray)

        val toolArray = JsonArray()
        tools.forEach { toolArray.add(it.deepCopy()) }
        payload.add("tools", toolArray)

        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(JSON, payload.toString()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful()) {
                throw IllegalStateException("DeepSeek HTTP ${response.code()}: $body")
            }
            return@withContext JsonParser.parseString(body).asJsonObject
        }
    }

    private fun systemMessage(skills: List<SkillBrief>): JsonObject {
        val sb = StringBuilder()
        sb.append("你是一个 Android 智能体，负责理解用户意图并把任务规划到已注册的 Skill 上执行。\n")
        sb.append("可用 Skill 如下：\n")
        skills.forEachIndexed { idx, s ->
            sb.append("${idx + 1}. ${s.id} — ${s.description}\n   参数: ${s.inputSpec}\n")
        }
        sb.append("规则：\n")
        sb.append("- 当用户请求与某个 Skill 匹配时，只允许调用 run_skill 工具，并且 skill 必须是上面列表中的 id。\n")
        sb.append("- 尽力根据用户语气把 inputs 参数填全；缺少关键参数时用合理默认值；确实缺失再要求用户补充。\n")
        sb.append("- 如果用户只是闲聊、提问或没有匹配的 Skill（例如『需要登录、发消息』这类），就直接用中文回答，绝对不要编造 Skill 或工具。\n")
        sb.append("- 路径必须是设备上的绝对路径。")
        return message("system", sb.toString())
    }

    private fun runSkillTool(skills: List<SkillBrief>): JsonObject {
        val skillProp = JsonObject()
        skillProp.addProperty("type", "string")
        val enum = JsonArray()
        skills.forEach { enum.add(it.id) }
        skillProp.add("enum", enum)
        skillProp.addProperty("description", "要执行的 Skill id")

        val inputsProp = JsonObject()
        inputsProp.addProperty("type", "object")
        inputsProp.addProperty("description", skills.joinToString("\n") { "${it.id}: ${it.inputSpec}" })

        val properties = JsonObject()
        properties.add("skill", skillProp)
        properties.add("inputs", inputsProp)

        val required = JsonArray()
        required.add("skill")

        val parameters = JsonObject()
        parameters.addProperty("type", "object")
        parameters.add("properties", properties)
        parameters.add("required", required)

        val function = JsonObject()
        function.addProperty("name", TOOL_RUN_SKILL)
        function.addProperty("description", "执行一个已注册的多步 Skill 来完成任务")
        function.add("parameters", parameters)

        val tool = JsonObject()
        tool.addProperty("type", "function")
        tool.add("function", function)
        return tool
    }

    fun close() {
        http.connectionPool().evictAll()
    }

    private fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }
}
