package com.fde.aiagent.execution

import android.content.Context
import com.fde.aiagent.ai.AiClient
import com.fde.aiagent.ai.AiConfig
import com.fde.aiagent.ai.SkillBrief
import com.fde.aiagent.ai.ModelPlan
import com.fde.aiagent.appfunctions.AppFunctionCatalog
import com.fde.aiagent.appfunctions.AppFunctionDispatcher
import com.fde.aiagent.appfunctions.FunctionResult
import com.fde.aiagent.skill.SkillBuiltins
import com.fde.aiagent.skill.SkillEngine
import com.fde.aiagent.skill.SkillRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 总协调器 —— 把各层串成一条流水线：
 *
 *  `AiClient（理解意图/规划，支持 DeepSeek/千问/智谱/Kimi/Gemini 等供应商）`
 *      -> 决定执行某个 Skill（ModelPlan.RunSkill）或直接回答
 *  `SkillEngine（多步流程执行）`
 *      -> 每步经 PermissionGuard 检查后由 AppFunctionDispatcher / SkillBuiltins 执行
 *  全程通过 [ExecutionBus] 发布 [AgentEvent]，驱动 SVG 状态面板 / 日志 / 错误提示。
 *
 * Java / Kotlin UI 只依赖本类：addListener + runAgent + openImage。
 */
class AgentCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bus = ExecutionBus()
    private val guard = PermissionGuard(appContext)
    private val dispatcher = AppFunctionDispatcher(appContext, guard)
    private val registry = SkillRegistry(appContext)
    private val builtins = SkillBuiltins(appContext, guard)
    private val engine = SkillEngine(dispatcher, guard, builtins)
    private val running = AtomicBoolean(false)

    fun addListener(listener: ExecutionListener) = bus.addListener(listener)

    fun removeListener(listener: ExecutionListener) = bus.removeListener(listener)

    private fun emit(event: AgentEvent) = bus.publish(event)

    // ---------------- 完整智能体执行（含 AI 规划） ----------------

    fun runAgent(cfg: AiConfig, prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) {
            emit(AgentEvent.Logged("请输入要执行的指令。"))
            return
        }
        if (!running.compareAndSet(false, true)) {
            emit(AgentEvent.Logged("已有任务正在执行，请等待完成。"))
            return
        }

        scope.launch {
            var client: AiClient? = null
            try {
                emit(AgentEvent.FlowStarted)
                emit(AgentEvent.Logged("===== 运行智能体 ====="))

                val skills = registry.all()
                emit(AgentEvent.Logged("→ 已注册 Skills: " + skills.joinToString { it.id }))

                // 1) 所选 AI 供应商：理解意图 & 规划
                val planNode = FlowNode(
                    id = "plan",
                    label = "规划任务（理解用户意图）",
                    kind = FlowNodeKind.PLAN,
                )
                emit(AgentEvent.NodeStarted(planNode.asRunning()))

                val cli = AiClient(cfg) { line -> emit(AgentEvent.Logged(line)) }
                client = cli
                val plan = cli.plan(
                    p,
                    skills.map { SkillBrief(it.id, it.description, it.inputSpec()) },
                )

                when (plan) {
                    is ModelPlan.Answer -> {
                        emit(AgentEvent.NodeFinished(planNode.asDone("无需执行 Skill，直接回答")))
                        emit(AgentEvent.Logged("← ${plan.text}"))
                        emit(AgentEvent.FlowFinished(plan.text))
                    }

                    is ModelPlan.RunSkill -> {
                        emit(AgentEvent.NodeFinished(planNode.asDone("选择 Skill: ${plan.skillId}")))
                        emit(AgentEvent.Logged("→ 执行 Skill: ${plan.skillId}"))

                        val skill = registry.get(plan.skillId)
                        if (skill == null) {
                            emit(AgentEvent.NodeFailed(planNode.asFailed("未知 Skill: ${plan.skillId}"), "未知 Skill"))
                            emit(AgentEvent.FlowFailed("模型选择了不存在的 Skill: ${plan.skillId}"))
                        } else {
                            val result = engine.execute(skill, plan.inputs, bus)
                            emit(AgentEvent.Logged("→ Skill 执行结果: " + result.summary))
                            if (result.failed) emit(AgentEvent.FlowFailed(result.summary))
                            else emit(AgentEvent.FlowFinished(result.summary))
                        }
                    }
                }
            } catch (t: Throwable) {
                emit(AgentEvent.Logged("✖ 运行异常: ${t.javaClass.simpleName}: ${t.message}"))
                emit(AgentEvent.FlowFailed("运行异常: ${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                client?.close()
                running.set(false)
            }
        }
    }

    // ---------------- 直接（不经过模型）执行单个 AppFunction ----------------

    /** 「打开相册」按钮：跨应用调用相册 openImage。 */
    fun openImage(path: String, title: String) =
        directFunction(AppFunctionCatalog.FUNC_OPEN_IMAGE, mapOf("path" to path, "title" to title))

    /** 「播放视频」按钮：跨应用调用相册 playVideo。 */
    fun playVideo(path: String, title: String) =
        directFunction(AppFunctionCatalog.FUNC_PLAY_VIDEO, mapOf("path" to path, "title" to title))

    /** 图片滤镜编辑：跨应用调用相册 editImage（path 必填，filter/outputPath 可选）。 */
    fun editImage(path: String, filter: String? = null, outputPath: String? = null) =
        directFunction(
            AppFunctionCatalog.FUNC_EDIT_IMAGE,
            buildMap {
                put("path", path)
                filter?.takeIf { it.isNotBlank() }?.let { put("filter", it) }
                outputPath?.takeIf { it.isNotBlank() }?.let { put("outputPath", it) }
            },
        )

    /** 「打开设置页」按钮：跨应用调用 Settings 的 openSettingsPage。 */
    fun openSettingsPage(page: String) =
        directFunction(AppFunctionCatalog.FUNC_OPEN_SETTINGS_PAGE, mapOf("page" to page))

    /** 调节屏幕亮度：跨应用调用 Settings 的 adjustBrightness（action: brighten/dim，step 可选）。 */
    fun adjustBrightness(action: String, step: String? = null) =
        directFunction(
            AppFunctionCatalog.FUNC_ADJUST_BRIGHTNESS,
            buildMap {
                put("action", action)
                step?.takeIf { it.isNotBlank() }?.let { put("step", it) }
            },
        )

    /** 调节媒体音量：跨应用调用 Settings 的 adjustMediaVolume（action: volume_up/volume_down，step 可选）。 */
    fun adjustMediaVolume(action: String, step: String? = null) =
        directFunction(
            AppFunctionCatalog.FUNC_ADJUST_MEDIA_VOLUME,
            buildMap {
                put("action", action)
                step?.takeIf { it.isNotBlank() }?.let { put("step", it) }
            },
        )

    /** 开启/关闭深色主题：跨应用调用 Settings 的 setDarkTheme（action: on/off）。 */
    fun setDarkTheme(action: String) =
        directFunction(AppFunctionCatalog.FUNC_SET_DARK_THEME, mapOf("action" to action))

    /** 按包名或应用名打开应用：跨应用调用 Settings 的 openApp（packageName / appName 至少一个）。 */
    fun openApp(packageName: String? = null, appName: String? = null) =
        directFunction(
            AppFunctionCatalog.FUNC_OPEN_APP,
            buildMap {
                packageName?.takeIf { it.isNotBlank() }?.let { put("packageName", it) }
                appName?.takeIf { it.isNotBlank() }?.let { put("appName", it) }
            },
        )

    /** 删除（移入回收站）文件/文件夹：跨应用调用 DocumentsUI 的 deleteFile（path 必填）。 */
    fun deleteFile(path: String) =
        directFunction(AppFunctionCatalog.FUNC_DELETE_FILE, mapOf("path" to path))

    /** 移动源文件夹下的文件到目标目录：跨应用调用 DocumentsUI 的 moveFiles。 */
    fun moveFiles(sourcePath: String, destinationPath: String) =
        directFunction(
            AppFunctionCatalog.FUNC_MOVE_FILES,
            mapOf("sourcePath" to sourcePath, "destinationPath" to destinationPath),
        )

    /** 重命名文件/文件夹：跨应用调用 DocumentsUI 的 renameFolder（path + newName 必填）。 */
    fun renameFolder(path: String, newName: String) =
        directFunction(
            AppFunctionCatalog.FUNC_RENAME_FOLDER,
            mapOf("path" to path, "newName" to newName),
        )

    /** 清空系统回收站：跨应用调用 DocumentsUI 的 emptyTrash（无参数，不可恢复）。 */
    fun emptyTrash() =
        directFunction(AppFunctionCatalog.FUNC_EMPTY_TRASH, emptyMap())

    private fun directFunction(functionId: String, params: Map<String, String>) {
        if (!running.compareAndSet(false, true)) {
            emit(AgentEvent.Logged("已有任务正在执行，请等待完成。"))
            return
        }
        scope.launch {
            try {
                emit(AgentEvent.FlowStarted)
                emit(AgentEvent.Logged("===== 直接调用 AppFunction: $functionId ====="))

                val fn = AppFunctionCatalog.get(functionId)
                if (fn == null) {
                    emit(AgentEvent.FlowFailed("未知 AppFunction: $functionId"))
                    return@launch
                }

                val checkNode = FlowNode("check", "权限检查（PermissionGuard）", FlowNodeKind.CHECK)
                emit(AgentEvent.NodeStarted(checkNode.asRunning()))
                val issue = guard.checkAppFunctionExecution()
                if (issue != null) {
                    emit(AgentEvent.NodeFailed(checkNode.asFailed(issue), issue))
                    emit(AgentEvent.FlowFailed(issue))
                    return@launch
                }
                emit(AgentEvent.NodeFinished(checkNode.asDone("AppFunctions 可用（权限守卫通过）")))

                val fnNode = FlowNode("fn", fn.description.ifEmpty { fn.id }, FlowNodeKind.FUNCTION)
                emit(AgentEvent.NodeStarted(fnNode.asRunning()))
                when (val result = dispatcher.dispatch(fn, params)) {
                    is FunctionResult.Success -> {
                        emit(AgentEvent.NodeFinished(fnNode.asDone(result.displayText)))
                        emit(AgentEvent.Logged("✔ ${result.displayText}"))
                        emit(AgentEvent.FlowFinished(result.displayText))
                    }

                    is FunctionResult.Failure -> {
                        emit(AgentEvent.NodeFailed(fnNode.asFailed(result.displayText), result.displayText))
                        emit(AgentEvent.FlowFailed(result.displayText))
                    }
                }
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                emit(AgentEvent.Logged("✖ 运行异常: $msg"))
                emit(AgentEvent.FlowFailed("运行异常: $msg"))
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * 「功能说明」按钮：返回本智能体已有的功能总览与使用方法，展示在运行日志面板。
     */
    fun featureSummary(): String = buildString {
        appendLine("===== 功能说明 =====")
        appendLine()
        appendLine("本智能体 = AI 理解/规划 ＋ Skill 编排 ＋ 跨应用 AppFunction 执行。")
        appendLine("所选 AI 供应商负责理解指令并规划动作，Skill 编排多步骤流程，")
        appendLine("AppFunction 调用相册/设置/文件管理器等应用暴露的真实能力。")
        appendLine()
        appendLine("【使用步骤】")
        appendLine("1. 左上角选择 AI 供应商并填写 API Key（在各平台申请），模型可留空用默认；")
        appendLine("2. 在「智能体指令」框输入自然语言指令，点击「运行智能体」；")
        appendLine("3. 也可点击 🎤 语音输入，识别后勾选「识别后自动运行」便直接执行；")
        appendLine("4. 中栏 SVG 面板实时展示执行流程，右栏运行日志输出每一步的结果。")
        appendLine()
        appendLine("【可用功能与示例指令】")
        appendLine("□ 相册媒体（gallery）：打开图片、播放视频、给图片加滤镜另存")
        appendLine("  例：打开 /sdcard/Download/bella.jpg")
        appendLine("  例：播放 /sdcard/Movies/clip.mp4")
        appendLine("  例：把 /sdcard/Download/a.jpg 加黑白滤镜另存")
        appendLine("□ 系统设置（settings）：打开设置页、调节亮度/音量/静音、深色模式")
        appendLine("  例：打开 Wi-Fi 设置 / 打开显示设置 / 打开应用设置")
        appendLine("  例：把屏幕调亮一点（dim 调暗）")
        appendLine("  例：把声音调大 50%（volume_down 调小）")
        appendLine("  例：静音 / 取消静音 / 打开深色模式 / 关闭深色模式")
        appendLine("□ 打开应用（app_open）：按包名或应用名打开任意应用")
        appendLine("  例：打开设置 / 打开相册 / 打开 com.android.settings")
        appendLine("□ 文件管理（file_op）：删除（移入回收站）、移动、重命名、清空回收站")
        appendLine("  例：删除 桌面/a.txt")
        appendLine("  例：把 桌面/临时 里的文件移到 桌面/归档")
        appendLine("  例：把 报告.txt 改名为 报告2026.txt")
        appendLine("  例：清空回收站")
        appendLine("□ 文件搜索（file_search）：按后缀/关键字在目录中搜索文件")
        appendLine("  例：在 /mnt/sdcard/Pictures 里找 *.png")
        appendLine("□ 任务记录（task_create）：把文本写入文件并回读校验")
        appendLine("  例：把“写周报”记录到 /data/local/tmp/task.txt")
        appendLine()
        appendLine("【界面快捷入口】")
        appendLine("· 「打开相册」按钮：直接打开图片（先填好图片路径）；")
        appendLine("· 「打开设置页」下拉 + 按钮：直接打开选定的设置页；")
        appendLine("· 「清空日志」：清空右栏运行日志并重置 SVG 状态面板。")
    }
}
