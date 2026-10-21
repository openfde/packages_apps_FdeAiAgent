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

    /** 「权限说明」按钮：返回权限摘要。 */
    fun permissionSummary(): String = guard.describe()
}
