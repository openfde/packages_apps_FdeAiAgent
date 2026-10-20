package com.fde.aiagent.skill

import android.content.Context
import java.io.IOException

/**
 * Skill 注册表 —— 从 `assets/skills/<id>/SKILL.md` 装载全部 Skill。
 */
class SkillRegistry(context: Context) {

    private val appContext = context.applicationContext
    private val cache = LinkedHashMap<String, Skill>()

    /** 返回所有可用的 Skill（首次调用会扫描 assets 并解析）。 */
    fun all(): List<Skill> {
        if (cache.isNotEmpty()) return cache.values.toList()
        val names = runCatching { appContext.assets.list("skills") }.getOrNull() ?: emptyArray()
        for (dir in names.sorted()) {
            if (dir.isBlank()) continue
            val skill = load("skills/$dir", dir) ?: continue
            cache[skill.id] = skill
        }
        return cache.values.toList()
    }

    fun get(id: String): Skill? = all().firstOrNull { it.id == id || it.name == id }

    private fun load(basePath: String, fallbackId: String): Skill? {
        val raw = try {
            appContext.assets.open("$basePath/SKILL.md")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: IOException) {
            return null
        }
        return SkillParser.parse(raw, fallbackId)
    }
}
