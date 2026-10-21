package com.fde.aiagent.execution

/**
 * 统一的执行事件模型。
 *
 * 整条 Agent 流水线（所选 AI 供应商规划 → SkillEngine 执行 → AppFunction 分发）的所有状态变化，
 * 都通过 [AgentEvent] 对外广播：SVG 状态面板、运行日志、错误提示条都只依赖这一套事件，
 * 从而保证 UI 与执行状态始终一致。
 */
sealed interface AgentEvent {

    /** 一次新的执行开始（清空上一轮的流程节点）。 */
    data object FlowStarted : AgentEvent

    /** 某个流程节点开始执行（携带处于 RUNNING 状态的节点）。 */
    data class NodeStarted(val node: FlowNode) : AgentEvent

    /** 某个流程节点成功完成（携带 DONE 状态节点及输出）。 */
    data class NodeFinished(val node: FlowNode) : AgentEvent

    /** 某个流程节点因条件不满足被跳过（携带 SKIPPED 状态节点）。 */
    data class NodeSkipped(val node: FlowNode) : AgentEvent

    /** 某个流程节点执行失败（携带 FAILED 状态节点及错误信息）。 */
    data class NodeFailed(val node: FlowNode, val error: String) : AgentEvent

    /** 一条普通日志，用于运行日志控制台 / SVG 面板的日志尾部。 */
    data class Logged(val text: String) : AgentEvent

    /** 整个流程成功完成。 */
    data class FlowFinished(val summary: String) : AgentEvent

    /** 整个流程失败。 */
    data class FlowFailed(val error: String) : AgentEvent
}

/** 流程节点的类型（决定 SVG 面板中节点的图标/语义）。 */
enum class FlowNodeKind { PLAN, CHECK, FUNCTION, BUILTIN, REPORT }

/** 流程节点的运行状态（决定 SVG 面板中节点的颜色/标记）。 */
enum class FlowNodeStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** 一个流程节点：Skill 的每一步都会对应一个节点，规划阶段也对应一个 PLAN 节点。 */
data class FlowNode(
    val id: String,
    val label: String,
    val kind: FlowNodeKind,
    val status: FlowNodeStatus = FlowNodeStatus.PENDING,
    val output: String = "",
)

fun FlowNode.asRunning() = copy(status = FlowNodeStatus.RUNNING, output = "")
fun FlowNode.asDone(output: String) = copy(status = FlowNodeStatus.DONE, output = output)
fun FlowNode.asSkipped() = copy(status = FlowNodeStatus.SKIPPED, output = "")
fun FlowNode.asFailed(error: String) = copy(status = FlowNodeStatus.FAILED, output = error)
