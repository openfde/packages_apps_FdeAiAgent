package com.fde.aiagent

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fde.aiagent.execution.AgentCoordinator
import com.fde.aiagent.execution.AgentEvent
import com.fde.aiagent.execution.ExecutionListener
import com.fde.aiagent.ui.SvgStateRenderer

/**
 * FDE AI Agent —— 五层架构的界面入口。
 *
 *  - DeepSeek（[com.fde.aiagent.ai.DeepSeekClient]）：理解意图 + 规划任务；
 *  - Skill（[com.fde.aiagent.skill.SkillRegistry] / [com.fde.aiagent.skill.SkillEngine]）：
 *    可复用的多步骤流程（assets/skills/&lt;id&gt;/SKILL.md）；
 *  - AppFunctions（[com.fde.aiagent.appfunctions.AppFunctionCatalog] /
 *    [com.fde.aiagent.appfunctions.AppFunctionDispatcher]）：调用应用暴露的真实功能；
 *  - 权限与执行层（[com.fde.aiagent.execution.PermissionGuard] / AgentCoordinator)：
 *    统一的安全把关；
 *  - SVG 状态面板（[SvgStateRenderer]，
 *    res/layout/activity_ai_agent.xml 中的 WebView）：通过统一的执行事件更新
 *    流程节点、运行日志与错误提示。
 *
 * 本 Activity 只负责 UI 与事件订阅，不做任何业务逻辑。
 */
class MainActivity : Activity(), ExecutionListener {

    private val renderer = SvgStateRenderer()

    private lateinit var editKey: EditText
    private lateinit var editImagePath: EditText
    private lateinit var editVideoPath: EditText
    private lateinit var editPrompt: EditText
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var tvError: TextView
    private lateinit var tvStatus: TextView
    private lateinit var svgView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var coordinator: AgentCoordinator

    companion object {
        private const val TAG = "FdeAiAgent"
        private const val PREFS = "fde_ai_agent"
        private const val KEY_API = "api_key"
        private const val KEY_IMAGE_PATH = "image_path"
        private const val KEY_VIDEO_PATH = "video_path"
        private const val KEY_PROMPT = "prompt"

        private const val DEFAULT_IMAGE_PATH = "/mnt/sdcard/Pictures/aa.png"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_agent)

        actionBar?.hide()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        editKey = findViewById(R.id.editDeepSeekKey)
        editImagePath = findViewById(R.id.editImagePath)
        editVideoPath = findViewById(R.id.editVideoPath)
        editPrompt = findViewById(R.id.editAgentPrompt)
        tvLog = findViewById(R.id.tvAgentLog)
        svLog = findViewById(R.id.svLog)
        tvError = findViewById(R.id.tvError)
        tvStatus = findViewById(R.id.tvStatus)
        svgView = findViewById(R.id.svgStateView)

        // 恢复上次保存的配置
       // editKey.setText(prefs.getString(KEY_API, ""))
        editImagePath.setText(prefs.getString(KEY_IMAGE_PATH, DEFAULT_IMAGE_PATH))
        editVideoPath.setText(prefs.getString(KEY_VIDEO_PATH, ""))
        editPrompt.setText(prefs.getString(KEY_PROMPT, "打开 /mnt/sdcard/Pictures/aa.png 这张图片预览"))

        setupWebView()

        coordinator = AgentCoordinator(this)
        coordinator.addListener(this)

        findViewById<Button>(R.id.btnRunAgent).setOnClickListener { runAgent() }
        findViewById<Button>(R.id.btnOpenGallery).setOnClickListener {
            saveSettings()
            val path = editImagePath.text.toString().trim()
            if (path.isEmpty()) { toast("请填写图片路径"); return@setOnClickListener }
            appendLog("===== 直接调用相册 AppFunction: openImage =====")
            coordinator.openImage(path, defaultTitle(path))
        }
        findViewById<Button>(R.id.btnGrantFunctionPermission).setOnClickListener {
            appendLog(coordinator.permissionSummary())
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            tvLog.setText("等待运行…")
            renderer.reset()
            tvError.visibility = View.GONE
            setStatus("就绪", R.color.pc_text_secondary)
            renderSvg()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.removeListener(this)
    }

    // ---------------- 智能体 ----------------

    private fun runAgent() {
        val key = editKey.text.toString().trim()
        val prompt = editPrompt.text.toString().trim()
        saveSettings()

        if (key.isEmpty()) {
            toast("请先填写并保存 DeepSeek API Key")
            return
        }
        if (prompt.isEmpty()) {
            toast("请输入智能体指令")
            return
        }
        coordinator.runAgent(key, prompt)
    }

    // ---------------- 执行事件订阅（统一入口） ----------------

    override fun onEvent(event: AgentEvent) {
        runOnUiThread { handleEvent(event) }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.FlowStarted -> {
                renderer.reset()
                tvError.visibility = View.GONE
                setStatus("执行中…", R.color.pc_warning)
                renderSvg()
            }

            is AgentEvent.Logged -> {
                appendLog(event.text)
            }

            is AgentEvent.NodeStarted,
            is AgentEvent.NodeFinished,
            is AgentEvent.NodeSkipped,
            is AgentEvent.NodeFailed
            -> {
                renderer.onEvent(event)
                val node = when (event) {
                    is AgentEvent.NodeStarted -> event.node
                    is AgentEvent.NodeFinished -> event.node
                    is AgentEvent.NodeSkipped -> event.node
                    is AgentEvent.NodeFailed -> event.node
                    else -> null
                }
                node?.let { setStatus("执行中…", R.color.pc_warning) }
                if (event is AgentEvent.NodeFailed) showError(event.error)
                renderSvg()
            }

            is AgentEvent.FlowFinished -> {
                renderer.onEvent(event)
                appendLog("完成：${event.summary}")
                setStatus("完成", R.color.pc_success)
                renderSvg()
            }

            is AgentEvent.FlowFailed -> {
                renderer.onEvent(event)
                appendLog("失败：${event.error}")
                showError(event.error)
                setStatus("失败", R.color.pc_danger)
                renderSvg()
            }
        }
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun setStatus(text: String, colorRes: Int) {
        tvStatus.text = text
        tvStatus.setTextColor(getColor(colorRes))
    }

    private fun renderSvg() {
        val html =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>html,body{margin:0;padding:0;background:#F9FAFC;}</style></head>" +
                "<body>${renderer.render()}</body></html>"
        svgView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun setupWebView() {
        svgView.settings.apply {
            javaScriptEnabled = false
            defaultTextEncodingName = "utf-8"
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            @Suppress("DEPRECATION")
            layoutAlgorithm = WebSettings.LayoutAlgorithm.SINGLE_COLUMN
        }
        renderSvg()
    }

    // ---------------- 配置持久化 / UI 工具 ----------------

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_API, editKey.text.toString().trim())
            .putString(KEY_IMAGE_PATH, editImagePath.text.toString().trim())
            .putString(KEY_VIDEO_PATH, editVideoPath.text.toString().trim())
            .putString(KEY_PROMPT, editPrompt.text.toString().trim())
            .apply()
    }

    private fun defaultTitle(path: String): String {
        val name = path.substringAfterLast('/').ifEmpty { path }
        return name.substringBeforeLast('.').ifEmpty { name }
    }

    private fun appendLog(line: String) {
        val prev = tvLog.text.toString()
        tvLog.setText(prev + (if (prev.endsWith("\n") || prev.isEmpty()) "" else "\n") + line)
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
