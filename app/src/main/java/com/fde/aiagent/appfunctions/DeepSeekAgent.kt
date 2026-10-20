package com.fde.aiagent.appfunctions

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * The bridge this app is about: **DeepSeek decides what to call, AppFunctions performs the
 * cross-app call.**
 *
 * The DeepSeek tools target the media AppFunctions published by
 * `com.fde.gallery3d` (`MediaAppFunctionsService#openImage` / `#playVideo`). When the model emits
 * a tool call the agent executes it through the platform
 * `android.app.appfunctions.AppFunctionManager` (see [GalleryAppFunctionsClient]).
 *
 * The HTTP client uses OkHttp + Gson (both already present in the tree via
 * `down_static_libs`) against DeepSeek's OpenAI-compatible `/chat/completions` endpoint, so no
 * external KMP / ktor / kotlinx-serialization dependencies are needed.
 */
class DeepSeekAgent(
    private val context: Context,
    apiKey: String,
    private val log: (String) -> Unit,
) {

    companion object {
        /** Target package this agent drives. */
        const val TARGET_PACKAGE = GalleryAppFunctionsClient.GALLERY_PACKAGE

        /** DeepSeek OpenAI-compatible chat completions endpoint. */
        const val API_URL = "https://api.deepseek.com/chat/completions"

        /** Model identifier; DeepSeek's general purpose model. */
        const val DEFAULT_MODEL = "deepseek-chat"

        private val JSON = MediaType.parse("application/json; charset=utf-8")!!

        /**
         * Tool registry: the AppFunctions exposed by `com.fde.gallery3d`. Schema mirrors what the
         * gallery's `MediaAppFunctionsService` reads from the request `GenericDocument`
         * (`getPropertyString("path")`, `getPropertyString("title")`).
         */
        val KNOWN_TOOLS: List<DiscoveredTool> = listOf(
            DiscoveredTool(
                functionId = GalleryAppFunctionsClient.FUNC_OPEN_IMAGE,
                packageName = TARGET_PACKAGE,
                description = "在相册应用(com.fde.gallery3d)中打开指定绝对路径的图片预览",
                schemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "description": "图片的绝对路径，例如 /mnt/sdcard/Pictures/aa.png"
                        },
                        "title": {
                          "type": "string",
                          "description": "图片显示标题，例如 aa"
                        }
                      },
                      "required": ["path", "title"]
                    }
                """.trimIndent(),
            ),
            DiscoveredTool(
                functionId = GalleryAppFunctionsClient.FUNC_PLAY_VIDEO,
                packageName = TARGET_PACKAGE,
                description = "在相册应用(com.fde.gallery3d)中播放指定绝对路径的视频",
                schemaJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {
                          "type": "string",
                          "description": "视频的绝对路径，例如 /mnt/sdcard/Movies/clip.mp4"
                        },
                        "title": {
                          "type": "string",
                          "description": "视频显示标题"
                        }
                      },
                      "required": ["path", "title"]
                    }
                """.trimIndent(),
            ),
        )
    }

    /** One AppFunction exposed to the model as a DeepSeek tool. */
    data class DiscoveredTool(
        val functionId: String,
        val packageName: String,
        val description: String,
        val schemaJson: String,
    )

    private val apiKey = apiKey.trim()
    private val gallery = GalleryAppFunctionsClient(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** True when a cross-app AppFunction call can actually succeed on this install. */
    val canExecuteCrossApp: Boolean
        get() = gallery.isSupported && gallery.hasExecutePermission()

    /**
     * Runs one agent turn for [prompt]:
     * 1. send the system + user prompt together with the AppFunction tools to DeepSeek,
     * 2. let the model emit `tool_calls`,
     * 3. execute the requested AppFunction in the target app via the platform AppFunctionManager,
     * 4. feed the result back for up to [maxToolRounds] rounds and return the final answer.
     */
    suspend fun run(prompt: String, maxToolRounds: Int = 3): AgentRunResult {
        if (apiKey.isEmpty()) {
            return AgentRunResult.Failure("未配置 DeepSeek API Key，请先在界面上填写并保存。")
        }

        val tools = KNOWN_TOOLS
        val messages = mutableListOf<JsonObject>()
        messages += message(
            "system",
            "你是一个 Android 智能体。当用户想打开图片或播放视频时必须调用工具，不要自己编造" +
                "路径。path 必须是设备上的绝对路径；调用工具后再用一句中文总结执行结果。",
        )
        messages += message("user", prompt)

        log("→ POST $API_URL (model=$DEFAULT_MODEL, tools=${tools.size})")
        log("→ 跨应用执行权限(EXECUTE_APP_FUNCTIONS): " +
            if (canExecuteCrossApp) "已授予" else "未授予")

        var rounds = 0
        while (true) {
            val completion = withContext(Dispatchers.IO) { chatCompletions(messages, tools) }
            val choice = completion.getAsJsonArray("choices").firstOrNull()?.asJsonObject
                ?: return AgentRunResult.Failure("DeepSeek 没有返回任何 choice。")

            val assistant = choice.getAsJsonObject("message")
            val content = assistant.contentString()
            val toolCalls = assistant.getAsJsonArrayOrNull("tool_calls")

            if (toolCalls == null || toolCalls.size() == 0) {
                log("← 模型直接回答（未调用工具）")
                return AgentRunResult.Answered(content.orEmpty(), messages.size + 1)
            }

            // Keep the full assistant message (incl. tool_calls) for context continuity.
            messages += assistant.deepCopy()

            for (call in toolCalls) {
                val callObj = call.asJsonObject
                val callId = callObj.get("id")?.asString ?: ""
                val function = callObj.getAsJsonObject("function")
                val name = function.get("name")?.asString ?: ""
                val argsJson = function.getOrNull("arguments")?.asString ?: "{}"
                val args = runCatching { JsonParser.parseString(argsJson).asJsonObject }
                    .getOrNull() ?: JsonObject()
                log("← 模型请求调用: $name  参数: $argsJson")

                val resultText = executeTool(name, args)
                val toolMessage = message("tool", resultText)
                if (callId.isNotEmpty()) toolMessage.addProperty("tool_call_id", callId)
                messages += toolMessage
            }

            rounds++
            if (rounds >= maxToolRounds) {
                return AgentRunResult.Failure("工具调用轮数超过上限($maxToolRounds)。")
            }
        }
    }

    /**
     * Dispatches a model tool call to the corresponding AppFunction in [TARGET_PACKAGE].
     * Returns a short Chinese summary to feed back to the model.
     */
    private suspend fun executeTool(name: String, args: JsonObject): String {
        val tool = KNOWN_TOOLS.firstOrNull { it.functionId == name }
            ?: return "未知工具: $name"
        val path = args.getOrNull("path")?.asString
        val title = args.getOrNull("title")?.asString
            ?: path?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: ""
        if (path.isNullOrBlank()) {
            return "参数错误：${tool.functionId} 需要一个设备上的绝对路径 path"
        }

        val outcome = when (tool.functionId) {
            GalleryAppFunctionsClient.FUNC_OPEN_IMAGE -> gallery.openImage(path, title)
            GalleryAppFunctionsClient.FUNC_PLAY_VIDEO -> gallery.playVideo(path, title)
            else -> return "暂未实现: ${tool.functionId}"
        }
        log("  ↳ ${outcome.displayText}")
        return outcome.displayText
    }

    /** Blocking DeepSeek chat-completions round trip. Call from a background dispatcher. */
    private fun chatCompletions(
        messages: List<JsonObject>,
        tools: List<DiscoveredTool>,
    ): JsonObject {
        val payload = JsonObject()
        payload.addProperty("model", DEFAULT_MODEL)
        payload.addProperty("temperature", 0.0)

        val msgArray = JsonArray()
        messages.forEach { msgArray.add(it.deepCopy()) }
        payload.add("messages", msgArray)

        val toolArray = JsonArray()
        tools.forEach { tool ->
            val function = JsonObject()
            function.addProperty("name", tool.functionId)
            function.addProperty("description", tool.description)
            function.add("parameters", JsonParser.parseString(tool.schemaJson))
            val entry = JsonObject()
            entry.addProperty("type", "function")
            entry.add("function", function)
            toolArray.add(entry)
        }
        payload.add("tools", toolArray)

        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(JSON, payload.toString()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful()) {
                throw IllegalStateException("DeepSeek HTTP ${response.code()}: $body")
            }
            return JsonParser.parseString(body).asJsonObject
        }
    }

    fun close() {
        client.connectionPool().evictAll()
    }

    /** Builds a `{role, content}` chat message. */
    private fun message(role: String, content: String): JsonObject {
        val m = JsonObject()
        m.addProperty("role", role)
        m.addProperty("content", content)
        return m
    }

    private fun JsonObject.contentString(): String? {
        val v = get("content") ?: return null
        return if (v.isJsonNull) null else v.asString
    }

    private fun JsonObject.getAsJsonArrayOrNull(key: String): JsonArray? {
        val v = get(key) ?: return null
        return if (v.isJsonNull || !v.isJsonArray) null else v.asJsonArray
    }

    private fun JsonObject.getOrNull(key: String): com.google.gson.JsonElement? =
        get(key)?.takeUnless { it.isJsonNull }
}

/** Outcome of one [DeepSeekAgent.run] call. */
sealed interface AgentRunResult {
    data class Answered(val text: String, val messageCount: Int) : AgentRunResult
    data class Failure(val message: String) : AgentRunResult
}
