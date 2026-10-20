package com.fde.aiagent.skill

/**
 * 把一份 SKILL.md（YAML front-matter + Markdown 正文）解析成 [Skill]。
 */
object SkillParser {

    /** 解析失败时返回 null（调用方跳过该 Skill）。 */
    fun parse(raw: String, fallbackId: String): Skill? {
        val (front, body) = splitFrontMatter(raw) ?: run { return null }
        val meta = SkillFrontMatter.parse(front)

        val id = str(meta["name"]).ifEmpty { fallbackId }
        val steps = (meta["steps"] as? List<*>)?.mapNotNull { node ->
            node as? Map<*, *>
        }?.mapNotNull { map ->
            parseStep(map)
        } ?: emptyList()

        return Skill(
            id = id,
            name = str(meta["name"]).ifEmpty { fallbackId },
            description = str(meta["description"]),
            version = str(meta["version"]).ifEmpty { "1.0.0" },
            tags = strList(meta["tags"]),
            functions = strList(meta["functions"]),
            inputs = strMap(meta["inputs"]),
            requiredInputs = strList(meta["input_required"]),
            steps = steps,
            instructions = body.trim(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStep(map: Map<*, *>): SkillStep {
        val rawInputs = map["inputs"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val checkInputs = (map["check_inputs"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        return SkillStep(
            id = str(map["id"]),
            type = str(map["type"]).ifEmpty { Skill.TYPE_CHECK },
            description = str(map["description"]),
            check = str(map["check"]),
            checkInputs = checkInputs,
            function = str(map["function"]),
            action = str(map["action"]),
            inputs = strMap(rawInputs),
            runWhen = str(map["run_when"]),
            template = str(map["template"]),
        )
    }

    /** 返回 front-matter 与正文；没有合法 front-matter 时返回 null。 */
    private fun splitFrontMatter(raw: String): Pair<String, String>? {
        val lines = raw.lines()
        if (lines.isEmpty() || !lines[0].trim().startsWith("---")) return null
        var end = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") { end = i; break }
        }
        if (end < 0) return null
        val front = lines.subList(1, end).joinToString("\n")
        val body = lines.subList(end + 1, lines.size).joinToString("\n")
        return Pair(front, body)
    }

    private fun str(value: Any?): String = value?.toString()?.trim() ?: ""

    private fun strList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim() }
        null -> emptyList()
        else -> listOf(value.toString().trim())
    }

    private fun strMap(value: Any?): Map<String, String> {
        if (value !is Map<*, *>) return emptyMap()
        return value.entries
            .filter { it.key != null && it.value != null }
            .associate { it.key.toString() to it.value.toString().trim() }
    }
}
