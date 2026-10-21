package com.fde.aiagent

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.fde.aiagent.ai.AiConfig
import com.fde.aiagent.ai.AiProvider
import com.fde.aiagent.execution.AgentCoordinator
import com.fde.aiagent.execution.AgentEvent
import com.fde.aiagent.execution.ExecutionListener
import com.fde.aiagent.ui.SvgStateRenderer

/**
 * FDE AI Agent —— 五层架构的界面入口。
 *
 *  - 所选 AI 供应商（[com.fde.aiagent.ai.AiClient]，支持 DeepSeek/千问/智谱/Kimi/Gemini/自定义）：
 *    理解意图 + 规划任务；
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

    private lateinit var spProvider: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editBaseUrl: EditText
    private lateinit var lblBaseUrl: TextView
    private lateinit var editImagePath: EditText
    private lateinit var editVideoPath: EditText
    private lateinit var editPrompt: EditText
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var tvError: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModelInfo: TextView
    private lateinit var svgView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var coordinator: AgentCoordinator

    companion object {
        private const val TAG = "FdeAiAgent"
        private const val PREFS = "fde_ai_agent"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
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
        spProvider = findViewById(R.id.spProvider)
        editApiKey = findViewById(R.id.editApiKey)
        editModel = findViewById(R.id.editModel)
        editBaseUrl = findViewById(R.id.editBaseUrl)
        lblBaseUrl = findViewById(R.id.lblBaseUrl)
        editImagePath = findViewById(R.id.editImagePath)
        editVideoPath = findViewById(R.id.editVideoPath)
        editPrompt = findViewById(R.id.editAgentPrompt)
        tvLog = findViewById(R.id.tvAgentLog)
        svLog = findViewById(R.id.svLog)
        tvError = findViewById(R.id.tvError)
        tvStatus = findViewById(R.id.tvStatus)
        tvModelInfo = findViewById(R.id.tvModelInfo)
        svgView = findViewById(R.id.svgStateView)

        // AI 供应商选择：几套预设 + 自定义
        val providers = AiProvider.entries
        spProvider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providers.map { it.label },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 恢复上次保存的配置
        val savedProvider = prefs.getString(KEY_PROVIDER, AiProvider.DEEPSEEK.id)!!
        val providerIndex = AiProvider.indexOf(savedProvider).coerceAtLeast(0)
        spProvider.setSelection(providerIndex)
        editApiKey.setText(prefs.getString(KEY_API, ""))
        editModel.setText(prefs.getString(KEY_MODEL, ""))
        editBaseUrl.setText(prefs.getString(KEY_BASE_URL, ""))
        editImagePath.setText(prefs.getString(KEY_IMAGE_PATH, DEFAULT_IMAGE_PATH))
        editVideoPath.setText(prefs.getString(KEY_VIDEO_PATH, ""))
        editPrompt.setText(prefs.getString(KEY_PROMPT, "打开 /mnt/sdcard/Pictures/aa.png 这张图片预览"))

        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveSettings()
                onProviderChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                onProviderChanged()
            }
        }

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

        onProviderChanged() // 初次渲染（自定义时才显示 Base URL 字段）
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.removeListener(this)
    }

    /** 切换供应商时：Base URL 仅在选择“自定义”时显示；同步右上角 model 信息。 */
    private fun onProviderChanged() {
        val provider = AiProvider.fromId(selectedProviderId()) ?: AiProvider.DEEPSEEK
        val isCustom = provider == AiProvider.CUSTOM
        val baseVisibility = if (isCustom) View.VISIBLE else View.GONE
        lblBaseUrl.visibility = baseVisibility
        editBaseUrl.visibility = baseVisibility

        // 未手动填模型时，提示栏展示当前生效模型
        val effectiveModel = editModel.text.toString().trim()
            .ifEmpty { provider.defaultModel }
        tvModelInfo.text = "${provider.id} · model: $effectiveModel"
    }

    private fun selectedProviderId(): String =
        AiProvider.entries.getOrNull(spProvider.selectedItemPosition)?.id ?: AiProvider.DEEPSEEK.id

    private fun collectConfig(): AiConfig = AiConfig(
        providerId = selectedProviderId(),
        apiKey = editApiKey.text.toString().trim(),
        baseUrl = editBaseUrl.text.toString().trim(),
        model = editModel.text.toString().trim(),
    )

    // ---------------- 智能体 ----------------

    private fun runAgent() {
        val cfg = collectConfig()
        val prompt = editPrompt.text.toString().trim()
        saveSettings()

        if (cfg.apiKey.isEmpty()) {
            toast("请先选择 AI 供应商并填写 API Key")
            return
        }
        if (cfg.provider() == AiProvider.CUSTOM && cfg.resolvedBaseUrl().isEmpty()) {
            toast("请填写自定义 Base URL")
            return
        }
        if (prompt.isEmpty()) {
            toast("请输入智能体指令")
            return
        }
        coordinator.runAgent(cfg, prompt)
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
            .putString(KEY_PROVIDER, selectedProviderId())
            .putString(KEY_API, editApiKey.text.toString().trim())
            .putString(KEY_MODEL, editModel.text.toString().trim())
            .putString(KEY_BASE_URL, editBaseUrl.text.toString().trim())
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
