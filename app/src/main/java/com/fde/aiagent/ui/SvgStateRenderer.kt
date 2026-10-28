package com.fde.aiagent.ui

import android.content.Context
import android.content.res.Configuration
import com.fde.aiagent.R
import com.fde.aiagent.execution.AgentEvent
import com.fde.aiagent.execution.FlowNode
import com.fde.aiagent.execution.FlowNodeKind
import com.fde.aiagent.execution.FlowNodeStatus

/**
 * SVG 状态面板 —— 把统一的执行事件渲染成 SVG 流程图。
 *
 * 订阅 [AgentEvent] 驱动内部状态：
 *  - 每个 Skill 步骤对应一个节点，颜色/标记随状态变化（等待/执行中/成功/失败/跳过）；
 *  - 顶部显示当前阶段（待机 / 执行中 / 完成 / 失败）；
 *  - 底部展示错误提示横幅与最近 N 条运行日志。
 *
 * render() 生成完整 SVG 字符串，由 Activity 的 WebView 加载展示。
 * 所有文案来自 @string/…（自动适配中英文）；颜色根据系统 uiMode 在浅色/深色调色板间切换。
 */
class SvgStateRenderer(private val context: Context) {

    enum class Phase { IDLE, RUNNING, DONE, FAILED }

    /** 读取时的浅色 / 深色调色板。 */
    private data class Palette(
        val svgBg: String,
        val textPrimary: String,
        val textSecondary: String,
        val textRegular: String,
        val cardBg: String,
        val cardBorder: String,
        val lineColor: String,
        val errorBg: String,
        val errorText: String,
        val logBoxBg: String,
        val logBoxBorder: String,
        val pillIdleBg: String,
        val pillIdleFg: String,
        val pillRunningBg: String,
        val pillRunningFg: String,
        val pillDoneBg: String,
        val pillDoneFg: String,
        val pillFailedBg: String,
        val pillFailedFg: String,
    )

    private val lightPalette = Palette(
        svgBg = "#F9FAFC",
        textPrimary = "#303133",
        textSecondary = "#909399",
        textRegular = "#606266",
        cardBg = "#FFFFFF",
        cardBorder = "#DCDFE6",
        lineColor = "#C0C4CC",
        errorBg = "#FEF0F0",
        errorText = "#E64848",
        logBoxBg = "#F6F8FA",
        logBoxBorder = "#E4E7ED",
        pillIdleBg = "#E7EAEF",
        pillIdleFg = "#909399",
        pillRunningBg = "#FDF6EC",
        pillRunningFg = "#E6A23C",
        pillDoneBg = "#F0F9EB",
        pillDoneFg = "#67C23A",
        pillFailedBg = "#FEF0F0",
        pillFailedFg = "#F56C6C",
    )

    private val darkPalette = Palette(
        svgBg = "#15181C",
        textPrimary = "#E5EAF3",
        textSecondary = "#8A9099",
        textRegular = "#C0C4CC",
        cardBg = "#1D2126",
        cardBorder = "#343A41",
        lineColor = "#4D545E",
        errorBg = "#3A2326",
        errorText = "#F87171",
        logBoxBg = "#20252B",
        logBoxBorder = "#343A41",
        pillIdleBg = "#2A2F36",
        pillIdleFg = "#8A9099",
        pillRunningBg = "#3A2E1C",
        pillRunningFg = "#E6A23C",
        pillDoneBg = "#22301E",
        pillDoneFg = "#67C23A",
        pillFailedBg = "#3A2326",
        pillFailedFg = "#F56C6C",
    )

    /** 当前是否深色模式（每次渲染实时读取系统配置）。 */
    val isNight: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val palette: Palette
        get() = if (isNight) darkPalette else lightPalette

    /** HTML body 背景色（与 SVG 画布一致）。 */
    val canvasColor: String
        get() = palette.svgBg

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
    private var phaseLabel = context.getString(R.string.svg_phase_idle)

    val currentPhase: Phase get() = phase

    fun reset() {
        nodes.clear()
        logTail.clear()
        error = null
        phase = Phase.IDLE
        phaseLabel = context.getString(R.string.svg_phase_idle)
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.FlowStarted -> {
                reset()
                phase = Phase.RUNNING
                phaseLabel = context.getString(R.string.svg_phase_executing)
            }

            is AgentEvent.NodeStarted -> {
                nodes[event.node.id] = event.node
                phase = Phase.RUNNING
                phaseLabel = context.getString(R.string.svg_phase_executing_node, event.node.label)
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
                phaseLabel = context.getString(R.string.svg_phase_done)
                logTail.add(replaceNewlines(event.summary))
                while (logTail.size > maxLogTail) logTail.removeAt(0)
            }

            is AgentEvent.FlowFailed -> {
                phase = Phase.FAILED
                phaseLabel = context.getString(R.string.svg_phase_failed)
                error = event.error
            }
        }
    }

    /** 生成完整 SVG 字符串。 */
    fun render(): String {
        val p = palette
        val sb = StringBuilder()

        val errorLines = error?.let { wrap(it, 52) } ?: emptyList()
        val hasLogs = logTail.isNotEmpty()

        val errorH = if (errorLines.isEmpty()) 0 else errorLines.size * 16 + 18
        val logH = if (hasLogs) logTail.size * 17 + 30 else 0
        val flowH = nodes.size * nodeStep
        val totalH = flowTop + flowH + 8 + errorH + (if (errorH > 0) 10 else 0) +
            (if (logH > 0) 10 else 0) + logH + 16

        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$W\" height=\"$totalH\" viewBox=\"0 0 $W $totalH\">")
        sb.append("  <rect width=\"$W\" height=\"$totalH\" fill=\"${p.svgBg}\"/>")

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
        val p = palette
        // 标题
        sb.append("  <text x=\"34\" y=\"30\" font-size=\"16\" font-weight=\"bold\" fill=\"${p.textPrimary}\">")
            .append(context.getString(R.string.svg_title))
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
            context.getString(R.string.svg_legend_pending) to "#909399",
            context.getString(R.string.svg_legend_running) to "#E6A23C",
            context.getString(R.string.svg_legend_done) to "#67C23A",
            context.getString(R.string.svg_legend_failed) to "#F56C6C",
            context.getString(R.string.svg_legend_skipped) to "#B0B3B8",
        )
        var lx = 34
        val ly = 62
        for ((label, color) in legend) {
            sb.append("  <circle cx=\"").append(lx + 5).append("\" cy=\"").append(ly).append("\" r=\"4\" fill=\"").append(color).append("\"/>")
            sb.append("  <text x=\"").append(lx + 14).append("\" y=\"").append(ly + 4).append("\" font-size=\"11\" fill=\"${p.textSecondary}\">")
                .append(label).append("</text>")
            lx += 14 + textWidth(label, 11) + 16
        }
    }

    private fun drawNodes(sb: StringBuilder) {
        val p = palette
        if (nodes.isEmpty()) {
            sb.append("  <text x=\"34\" y=\"").append(flowTop + 30)
                .append("\" font-size=\"13\" fill=\"${p.textSecondary}\">")
                .append(context.getString(R.string.svg_empty)).append("</text>")
            return
        }

        val order = nodes.values.toList()
        order.forEachIndexed { index, node ->
            val top = flowTop + index * nodeStep
            val color = statusColor(node.status)
            val nextTop = flowTop + (index + 1) * nodeStep

            // 卡片
            sb.append("  <rect x=\"$nodeX\" y=\"$top\" width=\"$nodeW\" height=\"$nodeH\" rx=\"10\" fill=\"${p.cardBg}\" stroke=\"${p.cardBorder}\"/>")
            // 左侧状态条
            sb.append("  <rect x=\"$nodeX\" y=\"$top\" width=\"6\" height=\"$nodeH\" rx=\"3\" fill=\"$color\"/>")

            // 标题（编号 + label）
            val title = (index + 1).toString() + ". " + node.label
            sb.append("  <text x=\"${nodeX + 18}\" y=\"${top + 22}\" font-size=\"14\" font-weight=\"bold\" fill=\"${p.textPrimary}\">")
                .append(e(truncate(title, 46))).append("</text>")

            // 次行（kind + status + 首行输出）
            val detail = kindTag(node.kind) + " · " + statusText(node.status) +
                (if (node.output.isNotBlank()) " · " + firstLine(node.output) else "")
            sb.append("  <text x=\"${nodeX + 18}\" y=\"${top + 44}\" font-size=\"11\" fill=\"${p.textRegular}\">")
                .append(e(truncate(detail, 78))).append("</text>")

            // 连接线（含箭头）
            if (index < nodes.size - 1) {
                val cx = 250
                val fromY = top + nodeH
                val toY = nextTop
                sb.append("  <line x1=\"$cx\" y1=\"$fromY\" x2=\"$cx\" y2=\"$toY\" stroke=\"${p.lineColor}\" stroke-width=\"1.5\"/>")
                sb.append("  <polygon points=\"${cx - 4},${toY - 6} ${cx + 4},${toY - 6} ${cx},${toY}\" fill=\"${p.lineColor}\"/>")
            }
        }
    }

    private fun drawErrorBanner(sb: StringBuilder, y: Int, lines: List<String>, h: Int) {
        val p = palette
        sb.append("  <rect x=\"$nodeX\" y=\"$y\" width=\"$nodeW\" height=\"$h\" rx=\"8\" fill=\"${p.errorBg}\" stroke=\"#F56C6C\"/>")
        sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 18}\" font-size=\"12\" font-weight=\"bold\" fill=\"${p.errorText}\">")
            .append(context.getString(R.string.svg_error_banner)).append("</text>")
        lines.forEachIndexed { i, line ->
            sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 36 + i * 16}\" font-size=\"11\" fill=\"${p.errorText}\">")
                .append(e(line)).append("</text>")
        }
    }

    private fun drawLogBox(sb: StringBuilder, y: Int, h: Int) {
        val p = palette
        sb.append("  <rect x=\"$nodeX\" y=\"$y\" width=\"$nodeW\" height=\"$h\" rx=\"8\" fill=\"${p.logBoxBg}\" stroke=\"${p.logBoxBorder}\"/>")
        sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 18}\" font-size=\"12\" font-weight=\"bold\" fill=\"${p.textPrimary}\">")
            .append(context.getString(R.string.svg_log_box)).append("</text>")
        logTail.forEachIndexed { i, line ->
            sb.append("  <text x=\"${nodeX + 12}\" y=\"${y + 36 + i * 17}\" font-family=\"monospace\" font-size=\"11\" fill=\"${p.textRegular}\">")
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
        FlowNodeStatus.PENDING -> context.getString(R.string.svg_status_pending)
        FlowNodeStatus.RUNNING -> context.getString(R.string.svg_status_running)
        FlowNodeStatus.DONE -> context.getString(R.string.svg_status_done)
        FlowNodeStatus.FAILED -> context.getString(R.string.svg_status_failed)
        FlowNodeStatus.SKIPPED -> context.getString(R.string.svg_status_skipped)
    }

    private fun kindTag(kind: FlowNodeKind): String = when (kind) {
        FlowNodeKind.PLAN -> context.getString(R.string.svg_kind_plan)
        FlowNodeKind.CHECK -> context.getString(R.string.svg_kind_check)
        FlowNodeKind.FUNCTION -> context.getString(R.string.svg_kind_function)
        FlowNodeKind.BUILTIN -> context.getString(R.string.svg_kind_builtin)
        FlowNodeKind.REPORT -> context.getString(R.string.svg_kind_report)
    }

    private fun pillBg(): String = when (phase) {
        Phase.IDLE -> palette.pillIdleBg
        Phase.RUNNING -> palette.pillRunningBg
        Phase.DONE -> palette.pillDoneBg
        Phase.FAILED -> palette.pillFailedBg
    }

    private fun pillFg(): String = when (phase) {
        Phase.IDLE -> palette.pillIdleFg
        Phase.RUNNING -> palette.pillRunningFg
        Phase.DONE -> palette.pillDoneFg
        Phase.FAILED -> palette.pillFailedFg
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