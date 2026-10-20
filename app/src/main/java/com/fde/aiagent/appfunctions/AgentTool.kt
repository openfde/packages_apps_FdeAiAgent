package com.fde.aiagent.appfunctions

/**
 * One capability the DeepSeek model is allowed to ask for.
 *
 * [key] is the tool name sent to the chat-completions API; [openAiSchema] is the JSON Schema the
 * model fills in when it decides to call this tool.
 */
data class AgentTool(
    val key: String,
    val description: String,
    val openAiSchema: String,
)
