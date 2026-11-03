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

/** 一条规划出的 Skill 任务：在某个 Skill 上用一组入参执行一次。 */
data class SkillTask(
    val skillId: String,
    val inputs: Map<String, String>,
)

/**
 * 规划结果：一次用户请求可包含多条指令，对应多个 Skill 任务（按顺序执行、汇总结果），
 * 或者直接回答（闲聊 / 无匹配的 Skill）。
 */
sealed interface ModelPlan {
    data class RunSkills(val tasks: List<SkillTask>) : ModelPlan
    data class Answer(val text: String) : ModelPlan
}

/**
 * 多供应商 AI 客户端 —— 负责「理解用户意图 & 规划任务」。
 *
 * 只做两件事：
 *  1. 维持与所选供应商的 HTTP 会话（OkHttp + Gson，均已在树内）；
 *  2. 把用户 Prompt + 已注册 Skill 目录喂给模型，让它通过 `run_skills` 工具一次挑选一个或多个
 *     Skill 并填好入参（tasks 数组中一条指令对应一个任务）；模型若不调用工具则直接回答。
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
        /** 规划阶段暴露给模型的唯一工具（tasks 数组一次可承载多条指令）。 */
        const val TOOL_RUN_SKILLS = "run_skills"

        private val JSON = MediaType.parse("application/json; charset=utf-8")!!
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 规划入口：给定用户 [prompt] 与可用 [skills]，返回 [ModelPlan.RunSkills]（可能含多个任务）
     * 或 [ModelPlan.Answer]。会根据 [cfg.provider] 自动切换到 OpenAI 兼容或 Gemini 协议。
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

        // 一个回复里可能有多次 run_skills 调用（个别供应商返回并行工具调用），全部收集后合并。
        val tasks = ArrayList<SkillTask>()
        for (call in toolCalls) {
            val fn = call.asJsonObject.getAsJsonObject("function")
            val name = fn.get("name")?.asString ?: ""
            if (name == TOOL_RUN_SKILLS) {
                val argsRaw = fn.get("arguments")?.takeUnless { it.isJsonNull }?.asString ?: "{}"
                val args = runCatching { JsonParser.parseString(argsRaw).asJsonObject }.getOrNull()
                    ?: JsonObject()
                tasks.addAll(tasksFromArgs(args))
            }
        }

        if (tasks.isEmpty()) {
            return ModelPlan.Answer(content ?: appContext.getString(R.string.ai_unknown_tool))
        }
        logPlan(tasks)
        return ModelPlan.RunSkills(tasks)
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

        // 查找所有 functionCall part（Gemini 支持一次并行返回多个 functionCall），全部收集后合并。
        val tasks = ArrayList<SkillTask>()
        for (part in parts) {
            val fn = part.asJsonObject.get("functionCall")?.asJsonObject ?: continue
            val name = fn.get("name")?.asString ?: ""
            if (name == TOOL_RUN_SKILLS) {
                val args = fn.get("args")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                tasks.addAll(tasksFromArgs(args))
            }
        }

        if (tasks.isNotEmpty()) {
            logPlan(tasks)
            return ModelPlan.RunSkills(tasks)
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

        val taskProps = JsonObject()
        taskProps.add("skill", skillProp)
        taskProps.add("inputs", inputsProp)

        val taskRequired = JsonArray()
        taskRequired.add("skill")

        val task = JsonObject()
        task.addProperty("type", "object")
        task.add("properties", taskProps)
        task.add("required", taskRequired)

        val tasksProp = JsonObject()
        tasksProp.addProperty("type", "array")
        tasksProp.addProperty("description", appContext.getString(R.string.tool_tasks_desc))
        tasksProp.add("items", task)

        val properties = JsonObject()
        properties.add("tasks", tasksProp)

        val required = JsonArray()
        required.add("tasks")

        val parameters = JsonObject()
        parameters.addProperty("type", "object")
        parameters.add("properties", properties)
        parameters.add("required", required)

        val function = JsonObject()
        function.addProperty("name", TOOL_RUN_SKILLS)
        function.addProperty("description", appContext.getString(R.string.tool_run_skill_desc))
        function.add("parameters", parameters)

        val tool = JsonObject()
        tool.addProperty("type", "function")
        tool.add("function", function)
        return tool
    }

    /**
     * 从一次 run_skills 调用的参数中解析出任务列表。
     * 优先读 `tasks` 数组（一条用户指令对应一个 {skill, inputs}）；若模型只给出单个
     * `skill`+`inputs`（旧格式 / 单任务），也兼容成一个任务。
     */
    private fun tasksFromArgs(args: JsonObject): List<SkillTask> {
        val tasks = ArrayList<SkillTask>()
        args.get("tasks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
            if (item.isJsonObject) taskFromRunArgs(item.asJsonObject)?.let { tasks.add(it) }
        }
        if (tasks.isEmpty()) {
            taskFromRunArgs(args)?.let { tasks.add(it) }
        }
        return tasks
    }

    /** 从单个任务的 {skill, inputs} 里解析出 [SkillTask]；缺少 skill 时返回 null。 */
    private fun taskFromRunArgs(runArgs: JsonObject): SkillTask? {
        val skill = (runArgs.get("skill")?.takeUnless { it.isJsonNull }?.asString ?: "").trim()
        if (skill.isEmpty()) return null

        val inputs = LinkedHashMap<String, String>()
        runArgs.get("inputs")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
            ?.forEach { (k, v) ->
                if (!v.isJsonNull) {
                    inputs[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                }
            }
        return SkillTask(skill, inputs)
    }

    private fun logPlan(tasks: List<SkillTask>) {
        val detail = tasks.joinToString("; ") { "${it.skillId}{${it.inputs}}" }
        log(appContext.getString(R.string.ai_plan_log, detail))
    }

    fun close() {
        http.connectionPool().evictAll()
    }

    private fun message(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }
}
