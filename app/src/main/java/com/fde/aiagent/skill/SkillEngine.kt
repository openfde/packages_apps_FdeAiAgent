package com.fde.aiagent.skill

import android.content.Context
import com.fde.aiagent.R
import com.fde.aiagent.appfunctions.AppFunctionCatalog
import com.fde.aiagent.appfunctions.AppFunctionDispatcher
import com.fde.aiagent.appfunctions.FunctionResult
import com.fde.aiagent.execution.AgentEvent
import com.fde.aiagent.execution.ExecutionBus
import com.fde.aiagent.execution.FlowNode
import com.fde.aiagent.execution.FlowNodeKind
import com.fde.aiagent.execution.FlowNodeStatus
import com.fde.aiagent.execution.PermissionGuard
import com.fde.aiagent.execution.asDone
import com.fde.aiagent.execution.asFailed
import com.fde.aiagent.execution.asRunning
import com.fde.aiagent.execution.asSkipped
import java.util.concurrent.atomic.AtomicInteger

/** Skill 执行结果。 */
data class SkillRunResult(
    val summary: String,
    val failed: Boolean,
)

/**
 * Skill Engine —— 核心执行引擎。
 *
 * 职责：把 [Skill] 声明的一组步骤按顺序执行，把多个工具（AppFunction / 内置能力）
 * 编排成一个可复用的多步骤任务流程，并通过 [ExecutionBus] 发布统一的 [AgentEvent]，
 * 供 SVG 状态面板 / 日志 / 错误提示订阅。
 *
 * 每个步骤都会：
 *  1. 解析参数（支持 `$input.x` 与 `${stepId.output}` 引用）；
 *  2. 求值 `runWhen` 条件，不满足则标记 SKIPPED 并跳过；
 *  3. 发布 NodeStarted -> 执行 -> NodeFinished / NodeFailed；
 *  4. 失败即中断（fail-fast），把第一个失败原因作为整体结果返回。
 */
class SkillEngine(
    private val context: Context,
    private val dispatcher: AppFunctionDispatcher,
    private val guard: PermissionGuard,
    private val builtins: SkillBuiltins,
) {

    private val appContext = context.applicationContext

    /** 每次 execute() 的自增序号，用于给流程节点生成唯一 id（一次流程可能执行多个 Skill / 重复 Skill）。 */
    private val runSeq = AtomicInteger(0)

    suspend fun execute(skill: Skill, input: Map<String, String>, bus: ExecutionBus): SkillRunResult {
        val runId = runSeq.incrementAndGet()
        val outputs = LinkedHashMap<String, String>()
        for ((index, step) in skill.steps.withIndex()) {
            val node = FlowNode(
                // 前缀 skill 名与本次执行序号，避免多次执行 / 重复 Skill 在 SVG 面板上节点 id 冲突互相覆盖。
                id = "${skill.id}#$runId/${step.id}",
                label = step.description.ifEmpty { step.id },
                kind = step.kind(),
                status = FlowNodeStatus.PENDING,
            )

            if (!evalWhen(step.runWhen, input, outputs)) {
                bus.publish(AgentEvent.NodeSkipped(node.asSkipped()))
                bus.publish(
                    AgentEvent.Logged(
                        appContext.getString(R.string.log_skip_step, step.id, step.runWhen),
                    ),
                )
                continue
            }

            bus.publish(AgentEvent.NodeStarted(node.asRunning()))
            try {
                val resolved = resolveInputs(step.inputs, input, outputs)
                val out = executeStep(step, resolved, input, outputs)
                outputs[step.id] = out
                bus.publish(AgentEvent.NodeFinished(node.asDone(out)))
                bus.publish(AgentEvent.Logged(appContext.getString(R.string.log_step_ok, step.id, out.oneLine())))
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                outputs[step.id] = msg
                bus.publish(AgentEvent.NodeFailed(node.asFailed(msg), msg))
                bus.publish(AgentEvent.Logged(appContext.getString(R.string.log_step_failed, step.id, msg)))
                return SkillRunResult(
                    summary = appContext.getString(R.string.summary_step_failed, skill.id, index + 1, step.id, msg),
                    failed = true,
                )
            }
        }

        val summary = summaryOf(skill, outputs)
        bus.publish(AgentEvent.Logged(appContext.getString(R.string.log_skill_completed, skill.id)))
        return SkillRunResult(summary = summary, failed = false)
    }

    private suspend fun executeStep(
        step: SkillStep,
        resolved: Map<String, String>,
        original: Map<String, String>,
        outputs: Map<String, String>,
    ): String = when (step.type) {
        Skill.TYPE_CHECK -> runCheck(step, original)
        Skill.TYPE_FUNCTION -> runFunction(step, resolved)
        Skill.TYPE_BUILTIN -> builtins.run(step.action, resolved)
        Skill.TYPE_REPORT -> resolveTemplate(step.template, original, outputs)
        else -> throw IllegalStateException(appContext.getString(R.string.unknown_step_type, step.type))
    }

    /** check 步骤：把判断逻辑集中到 PermissionGuard / 参数校验。 */
    private fun runCheck(step: SkillStep, input: Map<String, String>): String = when (step.check) {
        "appfunctions" -> guard.checkAppFunctionExecution()?.let { throw IllegalStateException(it) }
            ?: appContext.getString(R.string.check_app_ok)
        "local_write" -> if (guard.allowLocalWrite()) {
            appContext.getString(R.string.check_local_ok)
        } else {
            throw IllegalStateException(appContext.getString(R.string.check_local_fail))
        }
        "input" -> {
            val missing = step.checkInputs.filter { input[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(appContext.getString(R.string.check_input_fail, missing.joinToString()))
            }
            appContext.getString(R.string.check_input_ok, step.checkInputs.joinToString())
        }
        else -> appContext.getString(R.string.check_ok)
    }

    private suspend fun runFunction(step: SkillStep, resolved: Map<String, String>): String {
        val fn = AppFunctionCatalog.get(step.function)
            ?: throw IllegalStateException(appContext.getString(R.string.fn_not_in_catalog, step.function))
        return when (val result = dispatcher.dispatch(fn, resolved)) {
            is FunctionResult.Success -> result.displayText
            is FunctionResult.Failure -> throw IllegalStateException(result.displayText)
        }
    }

    private fun summaryOf(skill: Skill, outputs: Map<String, String>): String {
        val last = skill.steps.lastOrNull()
        if (last != null && last.type == Skill.TYPE_REPORT && outputs.containsKey(last.id)) {
            return outputs.getValue(last.id)
        }
        return appContext.getString(R.string.summary_completed, skill.id, outputs.size)
    }

    // ---------- 参数 / 模板 / 条件解析（纯逻辑委托给 SkillRefs，便于单测） ----------

    private fun resolveInputs(
        inputs: Map<String, String>,
        input: Map<String, String>,
        outputs: Map<String, String>,
    ): Map<String, String> = inputs.mapValues { (_, v) -> SkillRefs.resolveTemplate(v, input, outputs) }

    private fun resolveTemplate(
        template: String,
        input: Map<String, String>,
        outputs: Map<String, String>,
    ): String = SkillRefs.resolveTemplate(template, input, outputs)

    private fun evalWhen(expr: String, input: Map<String, String>, outputs: Map<String, String>): Boolean =
        SkillRefs.evalWhen(expr, input, outputs)

    private fun SkillStep.kind(): FlowNodeKind = when (type) {
        Skill.TYPE_CHECK -> FlowNodeKind.CHECK
        Skill.TYPE_FUNCTION -> FlowNodeKind.FUNCTION
        Skill.TYPE_BUILTIN -> FlowNodeKind.BUILTIN
        Skill.TYPE_REPORT -> FlowNodeKind.REPORT
        else -> FlowNodeKind.CHECK
    }

    private fun String.oneLine(): String {
        val single = replace("\n", " ⏎ ")
        return if (single.length <= 120) single else single.take(120) + "…"
    }
}
