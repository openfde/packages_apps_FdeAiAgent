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

/** 供规划使用的 Skill 摘要（避免 ai 层直接依赖 skill 层的完整模型）。 */
data class SkillBrief(
    val id: String,
    val description: String,
    val inputSpec: String,
)

/** 规划结果：要么选择某个 Skill 并填好参数，要么直接回答。 */
sealed interface ModelPlan {
    data class RunSkill(val skillId: String, val inputs: Map<String, String>) : ModelPlan
    data class Answer(val text: String) : ModelPlan
}

/**
 * 多供应商 AI 客户端 —— 负责「理解用户意图 & 规划任务」。
 *
 * 只做两件事：
 *  1. 维持与所选供应商的 HTTP 会话（OkHttp + Gson，均已在树内）；
 *  2. 把用户 Prompt + 已注册 Skill 目录喂给模型，让它通过 `run_skill` 工具选择一个
 *     Skill 并填好入参；模型若不调用工具则直接回答。
 *
 * 支持：
 *  - OpenAI 兼容协议（DeepSeek / 通义千问 / 智谱 GLM / Kimi / 自定义）：`chat/completions`
 *    端点 + `tools`/`tool_calls` 函数调用；
 *  - Google Gemini：`generateContent` 端点 + `functionDeclarations`/`functionCall`。
 *
 * 它**不直接调用 AppFunction / 内置工具** —— 具体执行交给 SkillEngine，
 * 从而把「做什么（规划）」与「怎么做（执行）」彻底解耦。
 */
class AiClient(
    /** 用户选择的供应商 / key / 模型 等配置。 */
    private val cfg: AiConfig,
    private val log: (String) -> Unit = {},
) {

    companion object {
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
     * 会根据 [cfg.provider] 自动切换到 OpenAI 兼容或 Gemini 协议。
     */
    suspend fun plan(prompt: String, skills: List<SkillBrief>): ModelPlan {
        if (cfg.apiKey.isBlank()) {
            throw IllegalStateException("未配置 API Key，请先在界面上选择供应商并填写 key。")
        }

        val provider = cfg.provider()
        val system = systemText(skills)
        val toolDef = runSkillTool(skills)

        return if (provider.openAiCompatible) {
            planOpenAi(system, prompt, toolDef)
        } else {
            planGemini(system, prompt, toolDef)
        }
    }

    // ---------------- OpenAI 兼容协议 ----------------

    private suspend fun planOpenAi(system: String, prompt: String, toolDef: JsonObject): ModelPlan {
        val url = cfg.resolvedBaseUrl()
        val model = cfg.resolvedModel()
        val messages = arrayListOf(message("system", system), message("user", prompt))
        log("→ POST $url (provider=${cfg.provider().id}, model=$model)")
        val completion = completeOpenAi(url, model, messages, listOf(toolDef))
        val choice = completion.getAsJsonArray("choices")?.asSequence()?.firstOrNull()?.asJsonObject
            ?: throw IllegalStateException("模型没有返回任何 choice。")
        return parseOpenAiChoice(choice)
    }

    private fun parseOpenAiChoice(choice: JsonObject): ModelPlan {
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
                return runSkillFromArgs(args)
                    ?: throw IllegalStateException("模型返回的 $TOOL_RUN_SKILL 缺少 skill 参数。")
            }
        }

        return ModelPlan.Answer(content ?: "模型请求了未知工具，请重试。")
    }

    /** 阻塞式 OpenAI 兼容 chat-completions 请求，须在 IO 调度器调用。 */
    private suspend fun completeOpenAi(
        url: String,
        model: String,
        messages: List<JsonObject>,
        tools: List<JsonObject>,
    ): JsonObject = withContext(Dispatchers.IO) {
        val payload = JsonObject()
        payload.addProperty("model", model)
        payload.addProperty("temperature", 0.0)

        val msgArray = JsonArray()
        messages.forEach { msgArray.add(it.deepCopy()) }
        payload.add("messages", msgArray)

        val toolArray = JsonArray()
        tools.forEach { toolArray.add(it.deepCopy()) }
        payload.add("tools", toolArray)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(JSON, payload.toString()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful()) {
                throw IllegalStateException("AI HTTP ${response.code()}: $body")
            }
            return@withContext JsonParser.parseString(body).asJsonObject
        }
    }

    // ---------------- Gemini（Google）协议 ----------------

    private suspend fun planGemini(system: String, prompt: String, toolDef: JsonObject): ModelPlan {
        val model = cfg.resolvedModel()
        // Gemini 端点形如 {base}/models/{model}:generateContent?key=xxx
        val url = "${cfg.resolvedBaseUrl()}/models/${model}:generateContent?key=${cfg.apiKey}"
        log("→ POST $url (provider=gemini, model=$model)")
        val resp = completeGemini(url, system, prompt, toolDef)
        return parseGeminiResponse(resp)
    }

    private fun parseGeminiResponse(resp: JsonObject): ModelPlan {
        val parts = resp.getAsJsonArray("candidates")?.asSequence()
            ?.mapNotNull { it.asJsonObject.getAsJsonObject("content")?.getAsJsonArray("parts") }
            ?.flatMap { it.asSequence() }
            ?.toList()
            .orEmpty()

        // 查找 functionCall part
        for (part in parts) {
            val fn = part.asJsonObject.get("functionCall")?.asJsonObject ?: continue
            val name = fn.get("name")?.asString ?: ""
            if (name == TOOL_RUN_SKILL) {
                val args = fn.get("args")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                return runSkillFromArgs(args)
                    ?: throw IllegalStateException("模型返回的 $TOOL_RUN_SKILL 缺少 skill 参数。")
            }
        }

        // 没有 functionCall：拼接所有纯文本 part 直接回答
        val text = parts.joinToString("") { part ->
            part.asJsonObject.get("text")?.takeUnless { it.isJsonNull }?.asString ?: ""
        }
        log("← 模型直接回答（不调用 Skill）")
        return ModelPlan.Answer(text)
    }

    /** 阻塞式 Gemini generateContent 请求，须在 IO 调度器调用。 */
    private suspend fun completeGemini(
        url: String,
        system: String,
        prompt: String,
        toolDef: JsonObject,
    ): JsonObject = withContext(Dispatchers.IO) {
        val payload = JsonObject()

        // systemInstruction（Gemini 不接受 contents 里的 system 角色）
        val si = JsonObject()
        val siParts = JsonArray()
        siParts.add(message("text", system))
        si.add("parts", siParts)
        payload.add("systemInstruction", si)

        // contents：单轮 user 消息
        val contents = JsonArray()
        val uc = JsonObject()
        uc.addProperty("role", "user")
        val uParts = JsonArray()
        uParts.add(message("text", prompt))
        uc.add("parts", uParts)
        contents.add(uc)
        payload.add("contents", contents)

        // tools -> functionDeclarations（复用 OpenAI 工具的 function 描述结构）
        val fds = JsonArray()
        fds.add(toolDef.getAsJsonObject("function").deepCopy())
        val tools = JsonObject()
        tools.add("functionDeclarations", fds)
        val toolArray = JsonArray()
        toolArray.add(tools)
        payload.add("tools", toolArray)

        val gen = JsonObject()
        gen.addProperty("temperature", 0.0)
        payload.add("generationConfig", gen)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(JSON, payload.toString()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful()) {
                throw IllegalStateException("Gemini HTTP ${response.code()}: $body")
            }
            return@withContext JsonParser.parseString(body).asJsonObject
        }
    }

    // ---------------- 共用的 Prompt / 工具描述 ----------------

    private fun systemText(skills: List<SkillBrief>): String {
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
        return sb.toString()
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

    /** 从工具参数解析出 [ModelPlan.RunSkill]；缺少 skill 时返回 null。 */
    private fun runSkillFromArgs(args: JsonObject): ModelPlan.RunSkill? {
        val skill = (args.get("skill")?.takeUnless { it.isJsonNull }?.asString ?: "").trim()
        if (skill.isEmpty()) return null

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

    fun close() {
        http.connectionPool().evictAll()
    }

    private fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }
}
