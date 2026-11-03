package com.fde.aiagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
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
import com.fde.aiagent.voice.SherpaVoiceRecognizer

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
 * 所有用户可见文案均来自 @string/…（中英文 + 深色模式自动适配）。
 */
class MainActivity : Activity(), ExecutionListener {

    private lateinit var renderer: SvgStateRenderer

    private lateinit var chkAutoRun: CheckBox
    private lateinit var btnVoiceInput: Button
    private lateinit var tvVoiceStatus: TextView
    private lateinit var editPrompt: EditText
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var tvError: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModelInfo: TextView
    private lateinit var svgView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var coordinator: AgentCoordinator

    /** 聊天窗口是否仍显示占位文案（第一条消息起清除）。 */
    private var chatEmpty = true

    /** 纯离线中文语音识别（sherpa-onnx，模型内置在 assets）。 */
    private var voiceRecognizer: SherpaVoiceRecognizer? = null

    companion object {
        private const val TAG = "FdeAiAgent"
        private const val PREFS = "fde_ai_agent"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_VOICE_AUTORUN = "voice_autorun"
        private const val REQ_RECORD_AUDIO = 1001
        private const val REQ_CONFIG = 1002


        /** 「打开设置页」下拉可选页面：key -> 显示名资源（与 Settings 的 openSettingsPage 保持一致）。 */
        private val SETTINGS_PAGES = listOf(
            "wifi" to R.string.settings_page_name_wifi,
            "display" to R.string.settings_page_name_display,
            "sound" to R.string.settings_page_name_sound,
            "apps" to R.string.settings_page_name_apps,
            "storage" to R.string.settings_page_name_storage,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_agent)

        actionBar?.hide()
        renderer = SvgStateRenderer(this)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        chkAutoRun = findViewById(R.id.chkAutoRun)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        editPrompt = findViewById(R.id.editAgentPrompt)
        tvLog = findViewById(R.id.tvAgentLog)
        svLog = findViewById(R.id.svLog)
        tvError = findViewById(R.id.tvError)
        tvStatus = findViewById(R.id.tvStatus)
        tvModelInfo = findViewById(R.id.tvModelInfo)
        svgView = findViewById(R.id.svgStateView)

        // 设置页选择：wifi/display/sound/apps/storage

        // 恢复上次保存的配置
        editPrompt.setText(prefs.getString(KEY_PROMPT, getString(R.string.default_prompt)))

        // 恢复语音输入配置
        chkAutoRun.isChecked = prefs.getBoolean(KEY_VOICE_AUTORUN, true)

        // 自动运行开关变化即保存
        chkAutoRun.setOnCheckedChangeListener { _, _ -> saveSettings() }

        editPrompt.setOnKeyListener { v, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                // 处理回车逻辑
                runAgent()
                true
            } else {
                false
            }
        }

        setupWebView()

        coordinator = AgentCoordinator(this)
        coordinator.addListener(this)

        // 纯离线语音识别：后台预加载 sherpa-onnx 中文模型
        voiceRecognizer = SherpaVoiceRecognizer(this).also { it.load(voiceListener) }

        findViewById<Button>(R.id.btnRunAgent).setOnClickListener { runAgent() }
        btnVoiceInput.setOnClickListener { toggleVoiceInput() }
        findViewById<ImageButton>(R.id.btnConfig).setOnClickListener {
            startActivityForResult(
                android.content.Intent(this, ConfigActivity::class.java),
                REQ_CONFIG,
            )
        }


        findViewById<ImageButton>(R.id.btnGrantFunctionPermission).setOnClickListener {
            // 功能说明改为在独立的 FeatureDescActivity 页面展示
            startActivity(android.content.Intent(this, FeatureDescActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnClearLog).setOnClickListener {
            tvLog.setText(getString(R.string.log_waiting))
            chatEmpty = true
            renderer.reset()
            tvError.visibility = View.GONE
            setStatus(getString(R.string.status_ready), R.color.pc_text_secondary)
            renderSvg()
        }

        refreshModelInfo() // 初始渲染右上角 model 信息
    }

    override fun onResume() {
        super.onResume()
        // 从 ConfigActivity 返回后刷新右上角生效的 provider / model 信息
        refreshModelInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.removeListener(this)
        voiceRecognizer?.release()
        voiceRecognizer = null
    }

    // ---------------- 语音输入（完全离线 · sherpa-onnx 中文识别） ----------------

    /** 识别回调（所有回调都在主线程，由 [SherpaVoiceRecognizer] 保证）。 */
    private val voiceListener = object : SherpaVoiceRecognizer.Listener {
        override fun onModelLoading() {
            tvVoiceStatus.text = getString(R.string.voice_model_loading)
        }

        override fun onModelReady() {
            tvVoiceStatus.text = getString(R.string.voice_status_default)
        }

        override fun onModelError(message: String) {
            tvVoiceStatus.text = message
            toast(message)
        }

        override fun onPartial(text: String) {
            tvVoiceStatus.text = if (text.isBlank()) {
                getString(R.string.voice_listening)
            } else {
                getString(R.string.voice_recog_partial, text)
            }
        }

        override fun onResult(text: String) {
            handleRecognized(text)
        }

        override fun onError(message: String) {
            btnVoiceInput.text = getString(R.string.btn_voice_input)
            tvVoiceStatus.text = message
            toast(message)
        }
    }

    /** 麦克风按钮：模型就绪后点击开始录音识别；再点一次停止并输出整句结果。 */
    private fun toggleVoiceInput() {
        val recognizer = voiceRecognizer ?: return
        if (recognizer.isRecording()) {
            recognizer.stop()
            btnVoiceInput.text = getString(R.string.btn_voice_input)
            tvVoiceStatus.text = getString(R.string.voice_recognizing)
            return
        }
        if (recognizer.isLoading() || !recognizer.isReady()) {
            val msg = getString(R.string.voice_model_not_ready)
            tvVoiceStatus.text = msg
            toast(msg)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        recognizer.start(voiceListener)
        btnVoiceInput.text = getString(R.string.voice_stop_btn)
        tvVoiceStatus.text = getString(R.string.voice_listening)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val recognizer = voiceRecognizer
            if (recognizer != null && recognizer.isReady()) {
                recognizer.start(voiceListener)
                btnVoiceInput.text = getString(R.string.voice_stop_btn)
                tvVoiceStatus.text = getString(R.string.voice_listening)
            } else {
                val msg = getString(R.string.voice_model_not_ready)
                tvVoiceStatus.text = msg
                toast(msg)
            }
        } else {
            val msg = getString(R.string.voice_no_permission)
            tvVoiceStatus.text = msg
            toast(msg)
        }
    }

    /** 识别出文字：填入指令框，可按需自动运行智能体。 */
    private fun handleRecognized(text: String) {
        if (text.isBlank()) {
            val msg = getString(R.string.voice_no_match)
            tvVoiceStatus.text = msg
            toast(msg)
            return
        }
        editPrompt.setText(text)
        editPrompt.setSelection(text.length)
        tvVoiceStatus.text = getString(R.string.voice_recognized, text)
        if (chkAutoRun.isChecked) {
            // 自动运行：runAgent 里会把问题加入聊天窗口
            runAgent()
        } else {
            appendChatUser(text)
            toast(getString(R.string.voice_recognized_confirm))
        }
    }

    /** 读取共享配置，同步右上角 model 信息（AI 配置在 ConfigActivity 编辑）。 */
    private fun refreshModelInfo() {
        val provider = AiProvider.fromId(prefs.getString(KEY_PROVIDER, AiProvider.DEEPSEEK.id) ?: "")
            ?: AiProvider.DEEPSEEK
//        val model = prefs.getString(KEY_MODEL, "")?.trim().takeIf { it.isNotEmpty() }
//            ?: provider.defaultModel
//        tvModelInfo.text = getString(R.string.model_info_format, provider.id, model)
    }

    private fun collectConfig(): AiConfig = AiConfig(
        providerId = prefs.getString(KEY_PROVIDER, AiProvider.DEEPSEEK.id) ?: AiProvider.DEEPSEEK.id,
        apiKey = prefs.getString(KEY_API, "") ?: "",
        baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
        model = prefs.getString(KEY_MODEL, "") ?: "",
    )

    // ---------------- 智能体 ----------------

    private fun runAgent() {
        val cfg = collectConfig()
        val prompt = editPrompt.text.toString().trim()
        saveSettings()

        if (cfg.apiKey.isEmpty()) {
            toast(getString(R.string.toast_select_provider))
            return
        }
        if (cfg.provider() == AiProvider.CUSTOM && cfg.resolvedBaseUrl().isEmpty()) {
            toast(getString(R.string.toast_fill_custom_base_url))
            return
        }
        if (prompt.isEmpty()) {
            toast(getString(R.string.toast_enter_prompt))
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
                // 保留之前的结果：交由 renderer 提交上一轮面板并开启新的一轮
                renderer.onEvent(event)
                tvError.visibility = View.GONE
                setStatus(getString(R.string.status_executing), R.color.pc_warning)
                renderSvg()
            }

            is AgentEvent.UserMessage -> {
                // 聊天窗口：显示用户问题，再展示后续的 AI 回复
                renderer.onEvent(event)
                appendChatUser(event.text)
                renderSvg()
            }

            is AgentEvent.Logged -> {
                // 原始运行日志不再进聊天窗口，仅汇入状态面板的日志尾部
                renderer.onEvent(event)
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
                node?.let { setStatus(getString(R.string.status_executing), R.color.pc_warning) }
                if (event is AgentEvent.NodeFailed) showError(event.error)
                renderSvg()
            }

            is AgentEvent.Answered -> {
                renderer.onEvent(event)
                appendChatAi(event.text)
                setStatus(getString(R.string.status_done), R.color.pc_success)
                renderSvg()
            }

            is AgentEvent.FlowFinished -> {
                renderer.onEvent(event)
                appendChatAi(event.summary)
                setStatus(getString(R.string.status_done), R.color.pc_success)
                renderSvg()
            }

            is AgentEvent.FlowFailed -> {
                renderer.onEvent(event)
                appendChatAi(event.error, failed = true)
                showError(event.error)
                setStatus(getString(R.string.status_failed), R.color.pc_danger)
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
        val bg = renderer.canvasColor
        val html =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>html,body{margin:0;padding:0;background:$bg;}</style></head>" +
                "<body>${renderer.render()}</body></html>"
        svgView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun setupWebView() {
        svgView.settings.apply {
            // 内容全部为本地生成的 HTML（baseURL 为 null，无外部加载），
            // 开启 JS 以便渲染完成后精确滚动到底部。
            javaScriptEnabled = true
            defaultTextEncodingName = "utf-8"
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            @Suppress("DEPRECATION")
            layoutAlgorithm = WebSettings.LayoutAlgorithm.SINGLE_COLUMN
        }
        // 每次渲染完成后可靠地滑到最底部，保证最新的一问一答可见；
        // 再延迟一次是覆盖页面加载后 / 布局重排导致的高度变化。
        svgView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                scrollSvgToBottom(view)
                view.postDelayed({ scrollSvgToBottom(view) }, 150)
            }
        }
        renderSvg()
    }

    /** 按 HTML 实际渲染高度把 svgStateView 滚到最底部。 */
    private fun scrollSvgToBottom(view: WebView) {
        view.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight);", null)
    }

    // ---------------- 配置持久化 / UI 工具 ----------------

    /** 仅持久化首页这边的配置；AI 连接配置（供应商 / API Key / 模型 / Base URL）由 ConfigActivity 维护。 */
    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_PROMPT, editPrompt.text.toString().trim())
            .putBoolean(KEY_VOICE_AUTORUN, chkAutoRun.isChecked)
            .apply()
    }

    private fun defaultTitle(path: String): String {
        val name = path.substringAfterLast('/').ifEmpty { path }
        return name.substringBeforeLast('.').ifEmpty { name }
    }

    /** 聊天窗口：追加一个「一问一答」的消息块（用户问题 / AI 回复），最新在最底下。 */
    private fun appendChat(who: String, text: String, failed: Boolean = false) {
        if (chatEmpty) {
            tvLog.setText("")
            chatEmpty = false
        }
        val label = if (failed) "$who${getString(R.string.chat_failed_suffix)}" else who
        val block = "── $label ──\n$text"
        val prev = tvLog.text.toString()
        tvLog.setText((if (prev.isEmpty()) "" else prev + "\n\n") + block)
        svLog.post { svLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun appendChatUser(text: String) =
        appendChat(getString(R.string.chat_user_label), text)

    private fun appendChatAi(text: String, failed: Boolean = false) =
        appendChat(getString(R.string.chat_ai_label), text, failed)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
