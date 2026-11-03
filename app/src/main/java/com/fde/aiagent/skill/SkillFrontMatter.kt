package com.fde.aiagent.skill

import java.util.LinkedHashMap

/**
 * 面向 SKILL.md front-matter 的最小 YAML 子集解析器。
 *
 * 支持语法（本项目格式受控，仅用该子集）：
 *  - 标量：`key: value`（纯文本 / 双引号 / 单引号）
 *  - 嵌套 Map（缩进表示）：
 *      inputs:
 *        path: 要打开的图片路径
 *  - 字符串列表：
 *      tags:
 *        - media
 *        - gallery
 *  - Map 列表（每个 `- ` 项 + 缩进的续行键）：
 *      steps:
 *        - id: scan
 *          type: builtin
 *          inputs:
 *            dir: "$input.dir"
 *
 * 其余 YAML 特性（锚点、`[a,b]` 内联数组、`{..}` 流式 Map 等）不在支持范围内。
 */
object SkillFrontMatter {

    private class Line(val lineno: Int, val indent: Int, val content: String)

    fun parse(raw: String): LinkedHashMap<String, Any?> {
        val lines = raw.lines().mapIndexedNotNull { idx, text ->
            val trimmed = text.trimEnd()
            val indent = leadingSpaces(trimmed)
            val content = trimmed.substring(indent)
            if (content.isEmpty() || content.startsWith("#")) null else Line(idx + 1, indent, content)
        }
        val root = LinkedHashMap<String, Any?>()
        var i = 0
        while (i < lines.size) {
            i = if (lines[i].indent == 0) parseMap(lines, i, 0, root) else i + 1
        }
        return root
    }

    /** 解析一个 Map 块：从 [start] 开始，所有缩进 >= [baseIndent] 且为 `key: ...` 的行都收进 [out]。 */
    private fun parseMap(
        lines: List<Line>,
        start: Int,
        baseIndent: Int,
        out: LinkedHashMap<String, Any?>,
    ): Int {
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.indent < baseIndent) return i
            if (line.indent > baseIndent) { i++; continue }

            val parts = splitKeyValue(line.content)
            if (parts == null) {
                i++; continue
            }
            val key = parts.first
            val rest = parts.second
            val value = rest.trim()

            if (value.isEmpty()) {
                // 可能是嵌套 Map / 列表，也可能就是 null
                val next = lines.getOrNull(i + 1)
                if (next != null && next.indent > baseIndent && next.content.startsWith("- ")) {
                    val list = ArrayList<Any?>()
                    i = parseSeq(lines, i + 1, next.indent, list)
                    out[key] = list
                } else if (next != null && next.indent > baseIndent) {
                    val map = LinkedHashMap<String, Any?>()
                    i = parseMap(lines, i + 1, next.indent, map)
                    out[key] = map
                } else {
                    out[key] = null
                    i++
                }
            } else {
                out[key] = parseScalar(value)
                i++
            }
        }
        return i
    }

    /** 解析一个列表块：从 [start] 开始，所有 indent == [seqIndent] 的 `- ` 行都是一个元素。 */
    private fun parseSeq(lines: List<Line>, start: Int, seqIndent: Int, out: MutableList<Any?>): Int {
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (line.indent < seqIndent) return i
            if (line.indent != seqIndent || !line.content.startsWith("- ")) { i++; continue }

            val rest = line.content.substring(2).trim()
            if (rest.isEmpty()) { i++; continue }

            if (rest.contains(":")) {
                // 元素是 Map：dash 后的首行 `key: value` + 后续缩进续行
                val map = LinkedHashMap<String, Any?>()
                val parts = splitKeyValue(rest)
                if (parts == null) {
                    out.add(rest); i++; continue
                }
                val key = parts.first
                val value = parts.second.trim()
                if (value.isEmpty()) {
                    val next = lines.getOrNull(i + 1)
                    if (next != null && next.indent > seqIndent && next.content.startsWith("- ")) {
                        val list = ArrayList<Any?>()
                        i = parseSeq(lines, i + 1, next.indent, list)
                        map[key] = list
                    } else if (next != null && next.indent > seqIndent) {
                        val child = LinkedHashMap<String, Any?>()
                        i = parseMap(lines, i + 1, next.indent, child)
                        map[key] = child
                    } else {
                        map[key] = null
                        i++
                    }
                } else {
                    map[key] = parseScalar(value)
                    i++
                }

                // 消耗该 Map 的续行（缩进 > dash 缩进），直到遇到下一个同级 `- `
                while (i < lines.size && lines[i].indent > seqIndent) {
                    i = parseMap(lines, i, lines[i].indent, map)
                }
                out.add(map)
            } else {
                out.add(parseScalar(rest))
                i++
            }
        }
        return i
    }

    /** 找到首个不在引号内的 `:`，返回 (key, 冒号后的内容)。 */
    private fun splitKeyValue(content: String): Pair<String, String>? {
        var quote: Char? = null
        for ((idx, ch) in content.withIndex()) {
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                ':' -> return Pair(content.substring(0, idx).trim(), content.substring(idx + 1))
            }
        }
        return null
    }

    private fun parseScalar(raw: String): Any? {
        var v = raw.trim()
        if (v.length >= 2) {
            val first = v.first()
            if ((first == '"' || first == '\'') && v.last() == first) {
                v = v.substring(1, v.length - 1)
            }
        }
        return when (v) {
            "true" -> true
            "false" -> false
            "null", "~" -> null
            else -> v
        }
    }

    private fun leadingSpaces(s: String): Int {
        var n = 0
        while (n < s.length && s[n] == ' ') n++
        return n
    }
}
