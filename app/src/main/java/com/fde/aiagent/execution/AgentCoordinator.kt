package com.fde.aiagent.execution

import android.content.Context
import com.fde.aiagent.R
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
 *      -> 决定执行一个或多个 Skill 任务（ModelPlan.RunSkills，一次请求可含多条指令）或直接回答
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
    private val engine = SkillEngine(appContext, dispatcher, guard, builtins)
    private val running = AtomicBoolean(false)

    fun addListener(listener: ExecutionListener) = bus.addListener(listener)

    fun removeListener(listener: ExecutionListener) = bus.removeListener(listener)

    private fun emit(event: AgentEvent) = bus.publish(event)

    // ---------------- 完整智能体执行（含 AI 规划） ----------------

    fun runAgent(cfg: AiConfig, prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) {
            emit(AgentEvent.Logged(appContext.getString(R.string.log_enter_instruction)))
            return
        }
        if (!running.compareAndSet(false, true)) {
            emit(AgentEvent.Logged(appContext.getString(R.string.log_task_running)))
            return
        }

        scope.launch {
            var client: AiClient? = null
            try {
                emit(AgentEvent.FlowStarted)
//                emit(AgentEvent.Logged(appContext.getString(R.string.log_run_agent)))

                val skills = registry.all()
//                emit(
//                    AgentEvent.Logged(
//                        appContext.getString(R.string.log_registered_skills, skills.joinToString { it.id }),
//                    ),
//                )

                // 1) 所选 AI 供应商：理解意图 & 规划
                val planNode = FlowNode(
                    id = "plan",
                    label = appContext.getString(R.string.plan_node_label),
                    kind = FlowNodeKind.PLAN,
                )
                emit(AgentEvent.NodeStarted(planNode.asRunning()))

                val cli = AiClient(cfg, appContext) { line -> {
//                    emit(AgentEvent.Logged(line))
                }}
                client = cli
                val plan = cli.plan(
                    p,
                    skills.map { SkillBrief(it.id, it.description, it.inputSpec()) },
                )

                when (plan) {
                    is ModelPlan.Answer -> {
                        // model 直接回答：不调度任何 Skill / AppFunction，
                        // 以 Answered 事件驱动 SVG 面板展示回答结果卡片。
                        emit(AgentEvent.Logged("← ${plan.text}"))
                        emit(AgentEvent.Answered(plan.text))
                    }

                    is ModelPlan.RunSkills -> {
                        val tasks = plan.tasks
                        val label = if (tasks.size == 1) {
                            appContext.getString(R.string.plan_done_choose_skill, tasks[0].skillId)
                        } else {
                            appContext.getString(
                                R.string.plan_done_choose_skills,
                                tasks.size,
                                tasks.joinToString { it.skillId },
                            )
                        }
                        emit(AgentEvent.NodeFinished(planNode.asDone(label)))

                        if (tasks.isEmpty()) {
                            val msg = appContext.getString(R.string.ai_unknown_tool)
                            emit(AgentEvent.Logged(msg))
                            emit(AgentEvent.FlowFailed(msg))
                        } else {
                            // 依次执行每个 Skill 任务；单个任务失败不中断其余任务，最终根据是否有失败决定整体结果。
                            val summaries = ArrayList<String>()
                            var anyFailed = false
                            tasks.forEachIndexed { index, task ->
                                emit(
                                    AgentEvent.Logged(
                                        appContext.getString(
                                            R.string.log_execute_skill_multi,
                                            index + 1,
                                            tasks.size,
                                            task.skillId,
                                        ),
                                    ),
                                )

                                val skill = registry.get(task.skillId)
                                if (skill == null) {
                                    anyFailed = true
                                    val msg = appContext.getString(
                                        R.string.node_failed_unknown_skill,
                                        task.skillId,
                                    )
                                    emit(AgentEvent.Logged(msg))
                                    summaries.add(msg)
                                    return@forEachIndexed
                                }

                                val result = engine.execute(skill, task.inputs, bus)
                                emit(
                                    AgentEvent.Logged(
                                        appContext.getString(R.string.log_skill_result, result.summary),
                                    ),
                                )
                                summaries.add(result.summary)
                                if (result.failed) anyFailed = true
                            }

                            val combined = summaries.filter { it.isNotBlank() }
                                .ifEmpty { listOf(appContext.getString(R.string.plan_done_direct_answer)) }
                                .joinToString("\n")
                            emit(
                                AgentEvent.Logged(
                                    appContext.getString(R.string.log_multi_result, tasks.size, combined),
                                ),
                            )
                            if (anyFailed) emit(AgentEvent.FlowFailed(combined))
                            else emit(AgentEvent.FlowFinished(combined))
                        }
                    }
                }
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                emit(AgentEvent.Logged(appContext.getString(R.string.log_run_exception, msg)))
                emit(AgentEvent.FlowFailed(appContext.getString(R.string.flow_failed_exception, msg)))
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
            emit(AgentEvent.Logged(appContext.getString(R.string.log_task_running)))
            return
        }
        scope.launch {
            try {
                emit(AgentEvent.FlowStarted)
                emit(AgentEvent.Logged(appContext.getString(R.string.log_direct_function, functionId)))

                val fn = AppFunctionCatalog.get(functionId)
                if (fn == null) {
                    emit(AgentEvent.FlowFailed(appContext.getString(R.string.flow_failed_unknown_function, functionId)))
                    return@launch
                }

                val checkNode = FlowNode(
                    "check",
                    appContext.getString(R.string.check_node_label),
                    FlowNodeKind.CHECK,
                )
                emit(AgentEvent.NodeStarted(checkNode.asRunning()))
                val issue = guard.checkAppFunctionExecution()
                if (issue != null) {
                    emit(AgentEvent.NodeFailed(checkNode.asFailed(issue), issue))
                    emit(AgentEvent.FlowFailed(issue))
                    return@launch
                }
                emit(AgentEvent.NodeFinished(checkNode.asDone(appContext.getString(R.string.check_node_done))))

                val fnLabel =
                    if (fn.descRes != 0) appContext.getString(fn.descRes)
                    else fn.description.ifEmpty { fn.id }
                val fnNode = FlowNode("fn", fnLabel, FlowNodeKind.FUNCTION)
                emit(AgentEvent.NodeStarted(fnNode.asRunning()))
                when (val result = dispatcher.dispatch(fn, params)) {
                    is FunctionResult.Success -> {
                        emit(AgentEvent.NodeFinished(fnNode.asDone(result.displayText)))
                        emit(AgentEvent.Logged(appContext.getString(R.string.log_direct_ok, result.displayText)))
                        emit(AgentEvent.FlowFinished(result.displayText))
                    }

                    is FunctionResult.Failure -> {
                        emit(AgentEvent.NodeFailed(fnNode.asFailed(result.displayText), result.displayText))
                        emit(AgentEvent.FlowFailed(result.displayText))
                    }
                }
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                emit(AgentEvent.Logged(appContext.getString(R.string.log_run_exception, msg)))
                emit(AgentEvent.FlowFailed(appContext.getString(R.string.flow_failed_exception, msg)))
            } finally {
                running.set(false)
            }
        }
    }
}
