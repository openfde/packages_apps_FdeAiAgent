package com.fde.aiagent.appfunctions

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Java-friendly facade used by [MainActivity]:
 *
 *  - runs the DeepSeek agent and the direct AppFunction call on a background dispatcher,
 *  - streams log lines / results back on the main thread through [TextCallback].
 *
 * `object` maps to `AgentRunner.INSTANCE` from Java.
 */
object AgentRunner {

    /** Simple SAM callback for Java callers. */
    fun interface TextCallback {
        fun onText(text: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Directly executes the gallery's `openImage` AppFunction — the in-process equivalent of:
     * ```
     * cmd app_function execute-app-function \
     *   --package com.fde.gallery3d \
     *   --function 'com.fde.gallery3d.appfunctions.MediaAppFunctionsService#openImage' \
     *   --parameters '{"path": "...", "title": "..."}'
     * ```
     */
    fun openImage(context: Context, path: String, title: String, callback: TextCallback) {
        scope.launch {
            val result = GalleryAppFunctionsClient(context).openImage(path, title)
            mainHandler.post { callback.onText(result.displayText) }
        }
    }

    /** Runs the DeepSeek agent; streams [logCallback] lines and the final answer via [doneCallback]. */
    fun runAgent(
        context: Context,
        apiKey: String,
        prompt: String,
        logCallback: TextCallback?,
        doneCallback: TextCallback,
    ) {
        scope.launch {
            val agent = DeepSeekAgent(context, apiKey) { line ->
                mainHandler.post { logCallback?.onText(line) }
            }
            try {
                val result = agent.run(prompt)
                val text = when (result) {
                    is AgentRunResult.Answered -> result.text
                    is AgentRunResult.Failure -> "运行失败：${result.message}"
                }
                mainHandler.post { doneCallback.onText(text) }
            } catch (t: Throwable) {
                mainHandler.post {
                    doneCallback.onText("运行异常：${t.javaClass.simpleName}: ${t.message}")
                }
            } finally {
                agent.close()
            }
        }
    }
}
