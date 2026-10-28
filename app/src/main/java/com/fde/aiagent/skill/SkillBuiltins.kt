package com.fde.aiagent.skill

import android.content.Context
import com.fde.aiagent.R
import com.fde.aiagent.execution.PermissionGuard
import java.io.File
import java.nio.file.Files

/**
 * 内置（非跨应用）执行能力，供 [SkillEngine] 的 builtin 步骤使用。
 *
 * 目前提供：
 *  - `file_scan`：在指定目录递归（最多 3 层）搜索匹配文件；
 *  - `file_read_head`：读取文件开头若干行；
 *  - `file_create`：写入一个本地文件（应用私有目录任意写；私有目录之外需 CONTROL_SYSTEM）。
 */
class SkillBuiltins(
    private val context: Context,
    private val guard: PermissionGuard,
) {

    private val appContext = context.applicationContext

    fun run(action: String, input: Map<String, String>): String = when (action) {
        "file_scan" -> fileScan(input)
        "file_read_head" -> readHead(input)
        "file_create" -> createFile(input)
        else -> throw IllegalStateException(appContext.getString(R.string.builtin_unknown_action, action))
    }

    /** file_scan：base_dir(必填)、match(可选 默认*)、limit(可选 默认20)。 */
    private fun fileScan(m: Map<String, String>): String {
        val base = m["base_dir"]?.trim()?.ifEmpty { null }
            ?: throw IllegalStateException(appContext.getString(R.string.builtin_missing_param, "base_dir"))
        val rule = m["match"]?.trim()?.ifEmpty { "*" } ?: "*"
        val limit = (m["limit"]?.trim()?.toIntOrNull() ?: 20).coerceIn(1, 100)

        val dir = File(base)
        if (!dir.exists() || !dir.isDirectory) {
            throw IllegalStateException(appContext.getString(R.string.builtin_dir_missing, base))
        }

        val found = ArrayList<String>()
        Files.walk(dir.toPath(), 3).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .forEach { p ->
                    if (found.size >= limit) return@forEach
                    if (matches(p.fileName.toString(), rule)) found.add(p.toString())
                }
        }
        return appContext.getString(R.string.builtin_scan_result, found.size, base, rule) +
            if (found.isEmpty()) "" else "\n" + found.joinToString("\n") { " - $it" }
    }

    /** file_read_head：path(必填)、lines(可选 默认5)。 */
    private fun readHead(m: Map<String, String>): String {
        val path = m["path"]?.trim()?.ifEmpty { null }
            ?: throw IllegalStateException(appContext.getString(R.string.builtin_missing_param, "path"))
        val n = (m["lines"]?.trim()?.toIntOrNull() ?: 5).coerceIn(1, 50)
        val f = File(path)
        if (!f.exists() || !f.isFile) {
            throw IllegalStateException(appContext.getString(R.string.builtin_file_missing, path))
        }
        val head = f.useLines { it.take(n).joinToString("\n") }
        return appContext.getString(R.string.builtin_read_head, n) + (head.ifEmpty { appContext.getString(R.string.builtin_empty_file) })
    }

    /** file_create：path(必填)、content(可选)。输出为最终写入的绝对路径，供后续步骤引用。 */
    private fun createFile(m: Map<String, String>): String {
        val path = m["path"]?.trim()?.ifEmpty { null }
            ?: throw IllegalStateException(appContext.getString(R.string.builtin_missing_param, "path"))
        val content = m["content"] ?: ""

        // 权限守卫：应用私有目录任意写，其它位置必须持有 CONTROL_SYSTEM。
        val inApp = path.startsWith(appContext.filesDir.absolutePath) ||
            path.startsWith(appContext.cacheDir.absolutePath)
        if (!inApp && !guard.allowLocalWrite()) {
            throw IllegalStateException(appContext.getString(R.string.builtin_write_blocked, path))
        }

        val f = File(path)
        if (f.parentFile != null && !f.parentFile.exists() && !f.parentFile.mkdirs()) {
            throw IllegalStateException(appContext.getString(R.string.builtin_mkdir_failed, f.parentFile.absolutePath))
        }
        f.writeText(content, Charsets.UTF_8)
        return f.absolutePath
    }

    private fun matches(name: String, rule: String): Boolean {
        if (rule == "*") return true
        return if (rule.startsWith("*.")) name.endsWith(rule.substring(1))
        else name.contains(rule)
    }
}
