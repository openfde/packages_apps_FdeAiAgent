package com.fde.aiagent.skill

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
    private val dispatcher: AppFunctionDispatcher,
    private val guard: PermissionGuard,
    private val builtins: SkillBuiltins,
) {

    suspend fun execute(skill: Skill, input: Map<String, String>, bus: ExecutionBus): SkillRunResult {
        val outputs = LinkedHashMap<String, String>()
        for ((index, step) in skill.steps.withIndex()) {
            val node = FlowNode(
                id = step.id,
                label = step.description.ifEmpty { step.id },
                kind = step.kind(),
                status = FlowNodeStatus.PENDING,
            )

            if (!evalWhen(step.runWhen, input, outputs)) {
                bus.publish(AgentEvent.NodeSkipped(node.asSkipped()))
                bus.publish(AgentEvent.Logged("-> 跳过 ${step.id}（条件不满足: ${step.runWhen}）"))
                continue
            }

            bus.publish(AgentEvent.NodeStarted(node.asRunning()))
            try {
                val resolved = resolveInputs(step.inputs, input, outputs)
                val out = executeStep(step, resolved, input, outputs)
                outputs[step.id] = out
                bus.publish(AgentEvent.NodeFinished(node.asDone(out)))
                bus.publish(AgentEvent.Logged("✔ ${step.id}: ${out.oneLine()}"))
            } catch (t: Throwable) {
                val msg = "${t.javaClass.simpleName}: ${t.message}"
                outputs[step.id] = msg
                bus.publish(AgentEvent.NodeFailed(node.asFailed(msg), msg))
                bus.publish(AgentEvent.Logged("✖ ${step.id} 失败: $msg"))
                return SkillRunResult(summary = "'${skill.id}' 在第 ${index + 1} 步（${step.id}）失败: $msg", failed = true)
            }
        }

        val summary = summaryOf(skill, outputs)
        bus.publish(AgentEvent.Logged("✔ Skill '${skill.id}' 执行完成"))
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
        else -> throw IllegalStateException("未知步骤类型: ${step.type}")
    }

    /** check 步骤：把判断逻辑集中到 PermissionGuard / 参数校验。 */
    private fun runCheck(step: SkillStep, input: Map<String, String>): String = when (step.check) {
        "appfunctions" -> guard.checkAppFunctionExecution()?.let { throw IllegalStateException(it) }
            ?: "✔ AppFunctions 可用（EXECUTE_APP_FUNCTIONS 已授予）"
        "local_write" -> if (guard.allowLocalWrite()) {
            "✔ 本地写入权限已授予（CONTROL_SYSTEM）"
        } else {
            throw IllegalStateException("本地写入被权限守卫拦截（缺少 CONTROL_SYSTEM，仅应用私有目录可写）")
        }
        "input" -> {
            val missing = step.checkInputs.filter { input[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw IllegalStateException("缺少必填参数: ${missing.joinToString()}")
            }
            "✔ 参数校验通过: ${step.checkInputs.joinToString()}"
        }
        else -> "✔ 检查通过"
    }

    private suspend fun runFunction(step: SkillStep, resolved: Map<String, String>): String {
        val fn = AppFunctionCatalog.get(step.function)
            ?: throw IllegalStateException("目录中不存在 AppFunction: ${step.function}")
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
        return "Skill '${skill.id}' 执行完成（${outputs.size} 步）"
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
