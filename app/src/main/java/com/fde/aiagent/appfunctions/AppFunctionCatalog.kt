package com.fde.aiagent.appfunctions

/**
 * AppFunction 目录 —— 集中登记本智能体可以调用的所有 AppFunction。
 *
 * 目前只有相册 `com.fde.gallery3d` 对外暴露的媒体能力（openImage / playVideo）。
 * 新增能力时只需在这里加一条 [AppFunction]，Skill / AI 客户端 / 分发器无需改动。
 */
object AppFunctionCatalog {

    const val GALLERY_PACKAGE = "com.fde.gallery3d"
    const val GALLERY_SERVICE = "com.fde.gallery3d.appfunctions.MediaAppFunctionsService"

    const val FUNC_OPEN_IMAGE = "openImage"
    const val FUNC_PLAY_VIDEO = "playVideo"

    /** 在相册中打开指定绝对路径的图片预览。 */
    val OPEN_IMAGE = AppFunction(
        id = FUNC_OPEN_IMAGE,
        packageName = GALLERY_PACKAGE,
        serviceName = GALLERY_SERVICE,
        description = "在相册应用(com.fde.gallery3d)中打开指定绝对路径的图片预览",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "图片的绝对路径，例如 /mnt/sdcard/Pictures/aa.png" },
                "title": { "type": "string", "description": "图片显示标题，例如 aa" }
              },
              "required": ["path", "title"]
            }
        """.trimIndent(),
        requiredParams = listOf("path", "title"),
    )

    /** 在相册中播放指定绝对路径的视频。 */
    val PLAY_VIDEO = AppFunction(
        id = FUNC_PLAY_VIDEO,
        packageName = GALLERY_PACKAGE,
        serviceName = GALLERY_SERVICE,
        description = "在相册应用(com.fde.gallery3d)中播放指定绝对路径的视频",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "视频的绝对路径，例如 /mnt/sdcard/Movies/clip.mp4" },
                "title": { "type": "string", "description": "视频显示标题" }
              },
              "required": ["path", "title"]
            }
        """.trimIndent(),
        requiredParams = listOf("path", "title"),
    )

    val ALL: List<AppFunction> = listOf(OPEN_IMAGE, PLAY_VIDEO)

    private val byId: Map<String, AppFunction> = ALL.associateBy { it.id }

    fun get(id: String): AppFunction? = byId[id]
}
