package com.fde.aiagent.ui

import com.fde.aiagent.execution.AgentEvent
import com.fde.aiagent.execution.FlowNode
import com.fde.aiagent.execution.FlowNodeKind
import com.fde.aiagent.execution.FlowNodeStatus

/**
 * SVG 状态面板 —— 把统一的执行事件渲染成 SVG 流程图。
 *
 * 订阅 [AgentEvent] 驱动内部状态：
 *  - 每个 Skill 步骤对应一个节点，颜色/标记随状态变化（等待/执行中/成功/失败/跳过）；
 *  - 顶部显示当前阶段（规划中 / 执行 Skill xxx / 完成 / 失败）；
 *  - 底部展示错误提示横幅与最近 N 条运行日志。
 *
 * render() 生成完整 SVG 字符串，由 Activity 的 WebView 加载展示。
 */
class SvgStateRenderer {

    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    // 布局常量
    private val W = 500
    private val nodeX = 34
    private val nodeW = W - 68
    private val nodeH = 58
    private val nodeStep = 78
    private val flowTop = 92
    private val maxLogTail = 6

    private val nodes = LinkedHashMap<String, FlowNode>()
    private val logTail = ArrayList<String>()
    private var error: String? = null
    private var phase = Phase.IDLE
    private var phaseLabel = "待机"

    val currentPhase: Phase get() = phase

    fun reset() {
        nodes.clear()
        logTail.clear()
        error = null
        phase = Phase.IDLE
        phaseLabel = "待机"
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.FlowStarted -> {
                reset()
                phase = Phase.RUNNING
                phaseLabel = "执行中…"
            }

            is AgentEvent.NodeStarted -> {
                nodes[event.node.id] = event.node
                phase = Phase.RUNNING
                phaseLabel = "正在执行: ${event.node.label}"
            }

            is AgentEvent.NodeFinished -> {
                nodes[event.node.id] = event.node
            }

            is AgentEvent.NodeSkipped -> {
                nodes[event.node.id] = event.node
            }

            is AgentEvent.NodeFailed -> {
                nodes[event.node.id] = event.node
                error = event.error
            }

            is AgentEvent.Logged -> {
                logTail.add(replaceNewlines(event.text))
                while (logTail.size > maxLogTail) logTail.removeAt(0)
            }

            is AgentEvent.FlowFinished -> {
                phase = Phase.DONE
                phaseLabel = "完成"
                logTail.add(replaceNewlines(event.summary))
                while (logTail.size > maxLogTail) logTail.removeAt(0)
            }

            is AgentEvent.FlowFailed -> {
                phase = Phase.FAILED
                phaseLabel = "失败"
                error = event.error
            }
        }
    }

    /** 生成完整 SVG 字符串。 */
    fun render(): String {
        val sb = StringBuilder()

        val errorLines = error?.let { wrap(it, 52) } ?: emptyList()
        val hasLogs = logTail.isNotEmpty()

        val errorH = if (errorLines.isEmpty()) 0 else errorLines.size * 16 + 18
        val logH = if (hasLogs) logTail.size * 17 + 30 else 0
        val flowH = nodes.size * nodeStep
        val totalH = flowTop + flowH + 8 + errorH + (if (errorH > 0) 10 else 0) +
            (if (logH > 0) 10 else 0) + logH + 16

        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$W\" height=\"$totalH\" viewBox=\"0 0 $W $totalH\">")
        sb.append("  <rect width=\"$W\" height=\"$totalH\" fill=\"#F9FAFC\"/>")

        drawHeader(sb)
        drawNodes(sb)

        var y = flowTop + flowH + 8
        if (errorH > 0) {
            drawErrorBanner(sb, y, errorLines, errorH)
            y += errorH + 10
        }
        if (logH > 0) {
            drawLogBox(sb, y, logH)
        }

        sb.append("</svg>")
        return sb.toString()
    }

    // ---------- 绘制 ----------

    private fun drawHeader(sb: StringBuilder) {
        // 标题
        sb.append("  <text x=\"34\" y=\"30\" font-size=\"16\" font-weight=\"bold\" fill=\"#303133\">")
            .append("执行流程（SVG 状态面板）")
            .append("</text>")

        // 阶段胶囊（按中/英文字符宽度估算 + 超长截断）
        val pillTextLimit = 14
        val pillText = if (phaseLabel.length > pillTextLimit) phaseLabel.take(pillTextLimit) + "…" else phaseLabel
        val pillW = (textWidth(pillText, 12) + 26).coerceAtLeast(60)
        val pillX = W - pillW - 34
        sb.append("  <rect x=\"$pillX\" y=\"12\" width=\"$pillW\" height=\"24\" rx=\"12\" fill=\"").append(pillBg()).append("\"/>")
        sb.append("  <text x=\"").append(pillX + pillW / 2).append("\" y=\"28\" font-size=\"12\" text-anchor=\"middle\" fill=\"").append(pillFg()).append("\">")
            .append(e(pillText)).append("</text>")

        // 图例
        val legend = listOf(
            "等待" to "#909399",
            "执行" to "#E6A23C",
            "成功" to "#67C23A",
            "失败" to "#F56C6C",
            "跳过" to "#B0B3B8",
        )
        var lx = 34
        val ly = 62
        for ((label, color) in legend) {
            sb.append("  <circle cx=\"").append(lx + 5).append("\" cy=\"").append(ly).append("\" r=\"4\" fill=\"").append(color).append("\"/>")
            sb.append("  <text x=\"").append(lx + 14).append("\" y=\"").append(ly + 4).append("\" font-size=\"11\" fill=\"#909399\">")
                .append(label).append("</text>")
            lx += 14 + textWidth(label, 11) + 16
        }
    }

    private fun drawNodes(sb: StringBuilder) {
        if (nodes.isEmpty()) {
            sb.append("  <text x=\"34\" y=\"").append(flowTop + 30)
                .append("\" font-size=\"13\" fill=\"#909399\">暂无执行记录，运行一次任务后展示流程图。</text>")
            return
        }

        val order = nodes.values.toList()
        order.forEachIndexed { index, node ->
            val top = flowTop + index * nodeStep
            val color = statusColor(node.status)
            val nextTop = flowTop + (index + 1) * nodeStep

            // 卡片
            sb.append("  <rect x=\"$nodeX\" y=\"$top\" width=\"$nodeW\" height=\"$nodeH\" rx=\"10\" fill=\"#FFFFFF\" stroke=\"#DCDFE6\"/>")
            // 左侧状态条
            sb.append("  <rect x=\"$nodeX\" y=\"$top\" width=\"6\" height=\"$nodeH\" rx=\"3\" fill=\"$color\"/>")

            // 标题（编号 + label）
            val title = (index + 1).toString() + ". " + node.label
            sb.append("  <text x=\"${nodeX + 18}\" y=\"${top + 22}\" font-size=\"14\" font-weight=\"bold\" fill=\"#303133\">")
                .append(e(truncate(title, 46))).append("</text>")

            // 次行（kind + status + 首行输出）
            val detail = kindTag(node.kind) + " · " + statusText(node.status) +
                (if (node.output.isNotBlank()) " · " + firstLine(node.output) else "")
            sb.append("  <text x=\"${nodeX + 18}\" y=\"${top + 44}\" font-size=\"11\" fill=\"#606266\">")
                .append(e(truncate(detail, 78))).append("</text>")

            // 连接线（含箭头）
            if (index < nodes.size - 1) {
                val cx = 250
                val fromY = top + nodeH
                val toY = nextTop
                sb.append("  <line x1=\"$cx\" y1=\"$fromY\" x2=\"$cx\" y2=\"$toY\" stroke=\"#C0C4CC\" stroke-width=\"1.5\"/>")
                sb.append("  <polygon points=\"${cx - 4},${toY - 6} ${cx + 4},${toY - 6} ${cx},${toY}\" fill=\"#C0C4CC\"/>")
            }
        }
    }

    private fun drawErrorBanner(sb: StringBuilder, y: Int, lines: List<String>, h: Int) {
        sb.append("  <rect x=\"$nodeX\" y=\"$y\" width=\"$nodeW\" height=\"$h\" rx=\"8\" fill=\"#FEF0F0\" stroke=\"#F56C6C\"/>")
        sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 18}\" font-size=\"12\" font-weight=\"bold\" fill=\"#E64848\">错误提示</text>")
        lines.forEachIndexed { i, line ->
            sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 36 + i * 16}\" font-size=\"11\" fill=\"#E64848\">")
                .append(e(line)).append("</text>")
        }
    }

    private fun drawLogBox(sb: StringBuilder, y: Int, h: Int) {
        sb.append("  <rect x=\"$nodeX\" y=\"$y\" width=\"$nodeW\" height=\"$h\" rx=\"8\" fill=\"#F6F8FA\" stroke=\"#E4E7ED\"/>")
        sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 18}\" font-size=\"12\" font-weight=\"bold\" fill=\"#303133\">运行日志（尾部）</text>")
        logTail.forEachIndexed { i, line ->
            sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 36 + i * 17}\" font-family=\"monospace\" font-size=\"11\" fill=\"#606266\">")
                .append(e(truncate(line, 66))).append("</text>")
        }
    }

    // ---------- 工具 ----------

    private fun statusColor(status: FlowNodeStatus): String = when (status) {
        FlowNodeStatus.PENDING -> "#909399"
        FlowNodeStatus.RUNNING -> "#E6A23C"
        FlowNodeStatus.DONE -> "#67C23A"
        FlowNodeStatus.FAILED -> "#F56C6C"
        FlowNodeStatus.SKIPPED -> "#B0B3B8"
    }

    private fun statusText(status: FlowNodeStatus): String = when (status) {
        FlowNodeStatus.PENDING -> "等待"
        FlowNodeStatus.RUNNING -> "执行中"
        FlowNodeStatus.DONE -> "成功"
        FlowNodeStatus.FAILED -> "失败"
        FlowNodeStatus.SKIPPED -> "跳过"
    }

    private fun kindTag(kind: FlowNodeKind): String = when (kind) {
        FlowNodeKind.PLAN -> "规划"
        FlowNodeKind.CHECK -> "检查"
        FlowNodeKind.FUNCTION -> "AppFunction"
        FlowNodeKind.BUILTIN -> "内置工具"
        FlowNodeKind.REPORT -> "汇总"
    }

    private fun pillBg(): String = when (phase) {
        Phase.IDLE -> "#E7EAEF"
        Phase.RUNNING -> "#FDF6EC"
        Phase.DONE -> "#F0F9EB"
        Phase.FAILED -> "#FEF0F0"
    }

    private fun pillFg(): String = when (phase) {
        Phase.IDLE -> "#909399"
        Phase.RUNNING -> "#E6A23C"
        Phase.DONE -> "#67C23A"
        Phase.FAILED -> "#F56C6C"
    }

    private fun firstLine(text: String): String {
        val single = replaceNewlines(text)
        return truncate(single, 64)
    }

    private fun replaceNewlines(text: String): String = text.replace("\n", " ⏎ ")

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"

    /** 按字符宽度粗略换行（用于错误横幅）。 */
    private fun wrap(text: String, maxChars: Int): List<String> {
        val cleaned = replaceNewlines(text)
        if (cleaned.length <= maxChars) return listOf(cleaned)
        val lines = ArrayList<String>()
        var rest = cleaned
        while (rest.length > maxChars) {
            val cut = rest.indexOf(' ', maxChars).let { if (it in 0..maxChars + 20) it else maxChars }
            lines.add(rest.substring(0, cut.coerceAtLeast(1)).trim())
            rest = rest.substring(cut.coerceAtLeast(1)).trim()
        }
        if (rest.isNotEmpty()) lines.add(rest)
        return lines
    }

    /** 粗略文本宽度（CJK 按全角估算），用于胶囊/图例定位。 */
    private fun textWidth(text: String, fontSize: Int): Int {
        var w = 0
        for (ch in text) {
            w += if (ch.code > 0x7F) fontSize else fontSize * 5 / 9
        }
        return w
    }

    /** SVG 文本转义。 */
    private fun e(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
