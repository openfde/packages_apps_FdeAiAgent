package com.fde.aiagent.skill

/**
 * 一个可复用的多步骤 Skill，由 `assets/skills/<id>/SKILL.md` 解析而来。
 *
 * 元数据存于 YAML front-matter，正文为 Markdown 说明（可注入给所选 AI 模型作为上下文）。
 */
data class Skill(
    val id: String,
    val name: String = id,
    val description: String = "",
    val version: String = "1.0.0",
    val tags: List<String> = emptyList(),
    /** 本 Skill 允许使用的 AppFunction id 列表（用于目录展示/约束）。 */
    val functions: List<String> = emptyList(),
    /** 本 Skill 声明的输入参数：name -> 说明。 */
    val inputs: Map<String, String> = emptyMap(),
    /** 必填的输入参数名。 */
    val requiredInputs: List<String> = emptyList(),
    /** 有序的多步流程。 */
    val steps: List<SkillStep> = emptyList(),
    /** Markdown 正文（流程说明，可注入 LLM 上下文）。 */
    val instructions: String = "",
) {
    companion object {
        const val TYPE_CHECK = "check"
        const val TYPE_FUNCTION = "function"
        const val TYPE_BUILTIN = "builtin"
        const val TYPE_REPORT = "report"
    }

    /** 供所选 AI 模型规划用的输入说明（含必填标记）。 */
    fun inputSpec(): String {
        val parts = inputs.map { (name, desc) ->
            if (name in requiredInputs) "$name(必填): $desc" else "$name: $desc"
        }
        return if (parts.isEmpty()) "(无参数)" else parts.joinToString("；")
    }
}

/** Skill 流程中的一步。 */
data class SkillStep(
    val id: String,
    /** [Skill.TYPE_CHECK] / FUNCTION / BUILTIN / REPORT。 */
    val type: String,
    val description: String = "",
    /** check 步骤的检查类型：appfunctions / local_write / input。 */
    val check: String = "",
    /** check=input 时所需的必填输入参数名。 */
    val checkInputs: List<String> = emptyList(),
    /** function 步骤对应的 AppFunction id（见 AppFunctionCatalog）。 */
    val function: String = "",
    /** builtin 步骤对应的内置动作名（见 SkillBuiltins）。 */
    val action: String = "",
    /** 步骤参数（可直接写死，或用 $input.x / ${stepId.output} 引用）。 */
    val inputs: Map<String, String> = emptyMap(),
    /** 条件表达式（支持 true/false、$input.x、$input.x == "v"、$input.x != ""），空=总是执行。 */
    val runWhen: String = "",
    /** report 步骤的汇总模板（支持 $input.x / ${stepId.output} 引用）。 */
    val template: String = "",
)
