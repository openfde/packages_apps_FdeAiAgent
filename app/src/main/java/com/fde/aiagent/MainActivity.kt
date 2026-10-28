package com.fde.aiagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var spSettingsPage: Spinner
    private lateinit var spVoiceLang: Spinner
    private lateinit var chkAutoRun: CheckBox
    private lateinit var btnVoiceInput: Button
    private lateinit var tvVoiceStatus: TextView
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

    /** 语音识别器（系统 SpeechRecognizer + RecognitionListener）。 */
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizing = false

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
        private const val KEY_VOICE_LANG = "voice_lang"
        private const val KEY_VOICE_AUTORUN = "voice_autorun"
        private const val REQ_RECORD_AUDIO = 1001

        private const val DEFAULT_IMAGE_PATH = "/mnt/sdcard/Pictures/aa.png"

        /** 「打开设置页」下拉可选页面：key -> 中文名（与 Settings 的 openSettingsPage 保持一致）。 */
        private val SETTINGS_PAGES = listOf(
            "wifi" to "Wi-Fi",
            "display" to "显示",
            "sound" to "声音",
            "apps" to "应用",
            "storage" to "存储",
        )

        /** 语音识别语言：key -> 界面显示名。auto=自动检测中/英。 */
        private val VOICE_LANGS = listOf(
            "auto" to "自动检测（中/英）",
            "zh-CN" to "中文",
            "en-US" to "English",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_agent)

        actionBar?.hide()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        spProvider = findViewById(R.id.spProvider)
        spSettingsPage = findViewById(R.id.spSettingsPage)
        spVoiceLang = findViewById(R.id.spVoiceLang)
        chkAutoRun = findViewById(R.id.chkAutoRun)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
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

        // 设置页选择：wifi/display/sound/apps/storage
        spSettingsPage.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            SETTINGS_PAGES.map { (key, name) -> "$key（$name）" },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 语音识别语言选择：自动检测（中/英）/ 中文 / English
        spVoiceLang.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            VOICE_LANGS.map { it.second },
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

        // 恢复语音输入配置
        val savedVoiceLang = prefs.getString(KEY_VOICE_LANG, "auto")!!
        val voiceLangIndex = VOICE_LANGS.indexOfFirst { it.first == savedVoiceLang }.coerceAtLeast(0)
        spVoiceLang.setSelection(voiceLangIndex)
        chkAutoRun.isChecked = prefs.getBoolean(KEY_VOICE_AUTORUN, true)

        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveSettings()
                onProviderChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                onProviderChanged()
            }
        }

        // 语音识别语言选择变化即保存；自动运行开关同理
        spVoiceLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveSettings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        chkAutoRun.setOnCheckedChangeListener { _, _ -> saveSettings() }

        setupWebView()

        coordinator = AgentCoordinator(this)
        coordinator.addListener(this)

        findViewById<Button>(R.id.btnRunAgent).setOnClickListener { runAgent() }
        btnVoiceInput.setOnClickListener { toggleVoiceInput() }
        findViewById<Button>(R.id.btnOpenGallery).setOnClickListener {
            saveSettings()
            val path = editImagePath.text.toString().trim()
            if (path.isEmpty()) { toast("请填写图片路径"); return@setOnClickListener }
            appendLog("===== 直接调用相册 AppFunction: openImage =====")
            coordinator.openImage(path, defaultTitle(path))
        }
        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            saveSettings()
            val index = spSettingsPage.selectedItemPosition.coerceIn(0, SETTINGS_PAGES.lastIndex)
            val page = SETTINGS_PAGES.getOrNull(index)?.first ?: "wifi"
            appendLog("===== 直接调用设置 AppFunction: openSettingsPage($page) =====")
            coordinator.openSettingsPage(page)
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
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ---------------- 语音输入 ----------------

    /** 麦克风按钮：点击开始聆听，再次点击停止。 */
    private fun toggleVoiceInput() {
        if (recognizing) {
            stopListening("已停止录音")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                tvVoiceStatus.text = "未授予录音权限，无法语音输入"
                toast("未授予录音权限，无法使用语音输入")
            }
        }
    }

    /** 启动系统 SpeechRecognizer 聆听。 */
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvVoiceStatus.text = "设备未安装可用的语音识别服务"
            toast("设备未安装可用的语音识别服务")
            return
        }
        val lang = selectedVoiceLanguage()
        val recognizer = speechRecognizer
            ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(recognitionListener)
                speechRecognizer = it
            }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // 自动检测（中/英）：显式列出支持的两种语言，其余交给识别器匹配
            if (lang == "auto") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayOf("zh-CN", "en-US"))
            } else {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            }
        }

        recognizing = true
        btnVoiceInput.text = "⏹ 停止"
        tvVoiceStatus.text = "正在聆听…请对着麦克风说话"
        try {
            recognizer.startListening(intent)
        } catch (e: Throwable) {
            recognizing = false
            btnVoiceInput.text = "🎤 语音输入"
            tvVoiceStatus.text = "无法启动语音识别：${e.message}"
            toast("无法启动语音识别：${e.message}")
        }
    }

    private fun selectedVoiceLanguage(): String =
        VOICE_LANGS.getOrNull(spVoiceLang.selectedItemPosition)?.first ?: "auto"

    /** 主动停止聆听（不取消，等待结果回调）。 */
    private fun stopListening(msg: String) {
        speechRecognizer?.stopListening()
        recognizing = false
        btnVoiceInput.text = "🎤 语音输入"
        tvVoiceStatus.text = msg
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            runOnUiThread { tvVoiceStatus.text = "准备就绪，请开始说话…" }
        }

        override fun onBeginningOfSpeech() {
            runOnUiThread { tvVoiceStatus.text = "正在聆听…" }
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            runOnUiThread { tvVoiceStatus.text = "识别中…" }
        }

        override fun onError(error: Int) {
            runOnUiThread {
                recognizing = false
                btnVoiceInput.text = "🎤 语音输入"
                tvVoiceStatus.text = errorText(error)
                if (error != SpeechRecognizer.ERROR_CLIENT) toast(tvVoiceStatus.text.toString())
            }
        }

        override fun onResults(results: Bundle?) {
            runOnUiThread {
                recognizing = false
                btnVoiceInput.text = "🎤 语音输入"
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?: return@runOnUiThread
                handleRecognized(matches.mapNotNull { it })
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** 识别出文字：填入指令框，可按需自动运行智能体。 */
    private fun handleRecognized(matches: List<String>) {
        val text = matches.firstOrNull { it.isNotBlank() }
        if (text == null) {
            tvVoiceStatus.text = "未识别到文字，请重试"
            toast("未识别到文字，请重试")
            return
        }
        editPrompt.setText(text)
        editPrompt.setSelection(text.length)
        appendLog("🎤 语音输入: $text")
        tvVoiceStatus.text = "识别成功：$text"
        if (chkAutoRun.isChecked) runAgent() else toast("识别成功，请确认指令后运行")
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音失败（无可用麦克风或音频被占用）"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限，无法录音"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误，识别失败"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时，识别失败"
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务忙碌，请稍候再试"
        SpeechRecognizer.ERROR_SERVER -> "语音识别服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "长时间没有说话，已停止聆听"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误，无法开始识别"
        else -> "语音识别失败（错误码 code=$code）"
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
            .putString(KEY_VOICE_LANG, selectedVoiceLanguage())
            .putBoolean(KEY_VOICE_AUTORUN, chkAutoRun.isChecked)
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
