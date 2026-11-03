package com.fde.aiagent.skill

/**
 * Skill 步骤参数/模板/条件解析（纯 Kotlin，无 Android 依赖，可单元测试）。
 *
 * 支持的引用语法：
 *  - `$input.name`：取用户为 Skill 填写的输入参数；
 *  - `${stepId.output}`：取前面某一步的输出（未执行/未产出则显示 `(跳过:stepId)`）。
 */
object SkillRefs {

    private fun isIdentChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    /**
     * 替换模板中的 `$input.name` 与 `${stepId.output}` 两种引用。
     * 手写扫描代替正则，避免模板 `$` 与正则歧义。
     */
    fun resolveTemplate(
        template: String,
        input: Map<String, String>,
        outputs: Map<String, String>,
    ): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '$' && i + 1 < template.length) {
                val next = template[i + 1]
                if (next == '{') {
                    val close = template.indexOf('}', i + 2)
                    if (close != -1) {
                        val inner = template.substring(i + 2, close)
                        if (inner.endsWith(".output")) {
                            val id = inner.substring(0, inner.length - ".output".length)
                            sb.append(outputs[id] ?: "(跳过:$id)")
                            i = close + 1
                            continue
                        }
                    }
                } else if (next == 'i' && template.startsWith("input.", i + 1)) {
                    var j = i + 1 + "input.".length
                    val nameStart = j
                    while (j < template.length && isIdentChar(template[j])) j++
                    if (j > nameStart) {
                        sb.append(input[template.substring(nameStart, j)] ?: "")
                        i = j
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString().trim()
    }

    /** 极简条件求值：true/false、$input.x、$input.x == "v"、$input.x != ""、${id}.output 非空。 */
    fun evalWhen(expr: String, input: Map<String, String>, outputs: Map<String, String>): Boolean {
        val e = expr.trim()
        if (e.isEmpty()) return true
        if (e == "true") return true
        if (e == "false") return false

        if (e.endsWith("!=\"\"") || e.endsWith(" != \"\"")) {
            val ref = e.substringBeforeLast("!=").trim()
            return resolveValue(ref, input, outputs).isNotEmpty()
        }
        val eqIdx = e.indexOf("==")
        if (eqIdx >= 0 && e.endsWith("\"")) {
            val lhs = e.substring(0, eqIdx).trim()
            val rhsRaw = e.substring(eqIdx + 2).trim()
            if (rhsRaw.length >= 2 && rhsRaw.startsWith("\"") && rhsRaw.endsWith("\"")) {
                return resolveValue(lhs, input, outputs) == rhsRaw.substring(1, rhsRaw.length - 1)
            }
        }
        return resolveValue(e, input, outputs).isNotEmpty()
    }

    fun resolveValue(ref: String, input: Map<String, String>, outputs: Map<String, String>): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("\$input.")) {
            return input[trimmed.substring("input.".length + 1)] ?: ""
        }
        if (trimmed.startsWith("\${") && trimmed.endsWith(".output}")) {
            val id = trimmed.substring(2, trimmed.length - ".output}".length)
            return outputs[id] ?: ""
        }
        return ""
    }
}
