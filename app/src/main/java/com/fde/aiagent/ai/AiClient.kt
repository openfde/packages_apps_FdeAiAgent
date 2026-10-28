package com.fde.aiagent.ai

import android.content.Context
import com.fde.aiagent.R
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
    /** 用于读取本地化资源（提示词 / 错误信息 / 运行日志）。 */
    private val context: Context,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

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
            throw IllegalStateException(appContext.getString(R.string.ai_no_api_key))
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
        log(appContext.getString(R.string.ai_post_log, url, cfg.provider().id, model))
        val completion = completeOpenAi(url, model, messages, listOf(toolDef))
        val choice = completion.getAsJsonArray("choices")?.asSequence()?.firstOrNull()?.asJsonObject
            ?: throw IllegalStateException(appContext.getString(R.string.ai_no_choice))
        return parseOpenAiChoice(choice)
    }

    private fun parseOpenAiChoice(choice: JsonObject): ModelPlan {
        val msg = choice.getAsJsonObject("message")
        val content = msg.get("content")?.takeUnless { it.isJsonNull }?.asString
        val toolCalls = msg.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray

        if (toolCalls == null || toolCalls.size() == 0) {
            log(appContext.getString(R.string.ai_direct_answer))
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
                    ?: throw IllegalStateException(appContext.getString(R.string.ai_missing_skill_param, TOOL_RUN_SKILL))
            }
        }

        return ModelPlan.Answer(content ?: appContext.getString(R.string.ai_unknown_tool))
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
                throw IllegalStateException(appContext.getString(R.string.ai_http_error, response.code(), body))
            }
            return@withContext JsonParser.parseString(body).asJsonObject
        }
    }

    // ---------------- Gemini（Google）协议 ----------------

    private suspend fun planGemini(system: String, prompt: String, toolDef: JsonObject): ModelPlan {
        val model = cfg.resolvedModel()
        // Gemini 端点形如 {base}/models/{model}:generateContent?key=xxx
        val url = "${cfg.resolvedBaseUrl()}/models/${model}:generateContent?key=${cfg.apiKey}"
        log(appContext.getString(R.string.ai_post_log, url, "gemini", model))
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
                    ?: throw IllegalStateException(appContext.getString(R.string.ai_missing_skill_param, TOOL_RUN_SKILL))
            }
        }

        // 没有 functionCall：拼接所有纯文本 part 直接回答
        val text = parts.joinToString("") { part ->
            part.asJsonObject.get("text")?.takeUnless { it.isJsonNull }?.asString ?: ""
        }
        log(appContext.getString(R.string.ai_direct_answer))
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
                throw IllegalStateException(appContext.getString(R.string.ai_gemini_http_error, response.code(), body))
            }
            return@withContext JsonParser.parseString(body).asJsonObject
        }
    }

    // ---------------- 共用的 Prompt / 工具描述 ----------------

    private fun systemText(skills: List<SkillBrief>): String {
        val sb = StringBuilder()
        sb.append(appContext.getString(R.string.ai_prompt_intro))
        skills.forEachIndexed { idx, s ->
            sb.append(appContext.getString(R.string.ai_prompt_skill_item, (idx + 1), s.id, s.description))
            sb.append(appContext.getString(R.string.ai_prompt_skill_params, s.inputSpec))
        }
        sb.append(appContext.getString(R.string.ai_prompt_rules))
        return sb.toString()
    }

    private fun runSkillTool(skills: List<SkillBrief>): JsonObject {
        val skillProp = JsonObject()
        skillProp.addProperty("type", "string")
        val enum = JsonArray()
        skills.forEach { enum.add(it.id) }
        skillProp.add("enum", enum)
        skillProp.addProperty("description", appContext.getString(R.string.tool_skill_desc))

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
        function.addProperty("description", appContext.getString(R.string.tool_run_skill_desc))
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
        log(appContext.getString(R.string.ai_plan_log, skill, inputs.toString()))
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
