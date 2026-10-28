package com.fde.aiagent.appfunctions

/**
 * AppFunction 目录 —— 集中登记本智能体可以调用的所有 AppFunction。
 *
 * 目前有此能力：
 *  - 相册 `com.fde.gallery3d` 对外暴露的媒体能力（openImage / playVideo / editImage）；
 *  - 系统设置 `com.android.settings` 对外暴露的「设置控制 / 应用启动」能力：
 *    openSettingsPage（打开设置页）、adjustBrightness（调节屏幕亮度）、
 *    adjustMediaVolume（调节媒体音量）、setDarkTheme（开启/关闭深色主题）、
 *    openApp（按包名/应用名打开应用）。
 *
 * 新增能力时只需在这里加一条 [AppFunction]，Skill / AI 客户端 / 分发器无需改动。
 */
object AppFunctionCatalog {

    const val GALLERY_PACKAGE = "com.fde.gallery3d"
    const val GALLERY_SERVICE = "com.fde.gallery3d.appfunctions.MediaAppFunctionsService"

    const val SETTINGS_PACKAGE = "com.android.settings"
    const val SETTINGS_SERVICE =
        "com.android.settings.appfunctions.OpenSettingsPageAppFunctionService"

    const val FUNC_OPEN_IMAGE = "openImage"
    const val FUNC_PLAY_VIDEO = "playVideo"
    const val FUNC_EDIT_IMAGE = "editImage"
    const val FUNC_OPEN_SETTINGS_PAGE = "openSettingsPage"
    const val FUNC_ADJUST_BRIGHTNESS = "adjustBrightness"
    const val FUNC_ADJUST_MEDIA_VOLUME = "adjustMediaVolume"
    const val FUNC_SET_DARK_THEME = "setDarkTheme"
    const val FUNC_OPEN_APP = "openApp"

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

    /**
     * 给指定图片添加滤镜并另存（path 必填；filter 可选、缺省 black_white 黑白、origin 表示
     * 不处理仅另存副本；outputPath 可选、缺省写入 Pictures/fde 目录）。功能定义见 FdeGallery
     * 的 assets/appfunctions.xml 与 MediaAppFunctionsService.editImage。
     */
    val EDIT_IMAGE = AppFunction(
        id = FUNC_EDIT_IMAGE,
        packageName = GALLERY_PACKAGE,
        serviceName = GALLERY_SERVICE,
        description = "给指定图片添加滤镜并保存（filter 可选，如 black_white 黑白；outputPath 可指定输出路径）",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "源图片的绝对路径，例如 /sdcard/Download/bella.jpg" },
                "filter": { "type": "string", "description": "滤镜名称：black_white / bw（黑白）、sepia（怀旧）、retro（复古）、classic（经典）、soft（软化）、shine（发光）、mosaic（马赛克）、origin（仅另存副本）等，缺省 black_white" },
                "outputPath": { "type": "string", "description": "输出文件路径（PNG），缺省写入 Pictures/fde 目录" }
              },
              "required": ["path"]
            }
        """.trimIndent(),
        requiredParams = listOf("path"),
    )

    /** 打开指定的系统设置页面（参数 page，取值与 Settings 的 OpenSettingsPageAppFunctionService 一致）。 */
    val OPEN_SETTINGS_PAGE = AppFunction(
        id = FUNC_OPEN_SETTINGS_PAGE,
        packageName = SETTINGS_PACKAGE,
        serviceName = SETTINGS_SERVICE,
        description = "打开指定的系统设置页面（page 可选 wifi / display / sound / apps / storage）",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "page": { "type": "string", "description": "要打开的系统设置页 key：wifi / display / sound / apps / storage" }
              },
              "required": ["page"]
            }
        """.trimIndent(),
        requiredParams = listOf("page"),
    )

    /**
     * 调节屏幕亮度（参数 action：brighten 调亮 / dim 调暗；step 每次调节的亮度百分比，
     * 可选、默认 10、范围 1~100，settings 侧通过 getPropertyLongArray 读取，因此分发时
     * step 会用 Long 属性下发）。功能定义见 Settings 的 fdesettings_appfunctions.xml 与
     * OpenSettingsPageAppFunctionService.adjustBrightness。
     */
    val ADJUST_BRIGHTNESS = AppFunction(
        id = FUNC_ADJUST_BRIGHTNESS,
        packageName = SETTINGS_PACKAGE,
        serviceName = SETTINGS_SERVICE,
        description = "调节屏幕亮度（action: brighten 调亮 / dim 调暗；step 可选，每次调节的亮度百分比）",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "action": { "type": "string", "description": "亮度调节方向：brighten（调亮）/ dim（调暗）" },
                "step": { "type": "integer", "description": "每次调节的亮度百分比，默认 10，范围 1~100" }
              },
              "required": ["action"]
            }
        """.trimIndent(),
        requiredParams = listOf("action"),
    )

    /**
     * 调节媒体音量（参数 action：volume_up 调高 / volume_down 调低；step 每次调节的音量百分比，
     * 可选、默认 10、范围 1~100，映射到媒体音量的分档范围 [min, max]，settings 侧通过
     * getPropertyLongArray 读取 step，因此分发时 step 用 Long 属性下发，通过
     * AudioManager#setStreamVolume 写回）。返回结果带 before / after 百分比。
     * 功能定义见 Settings 的 fdesettings_appfunctions.xml 与
     * OpenSettingsPageAppFunctionService.adjustMediaVolume。
     */
    val ADJUST_MEDIA_VOLUME = AppFunction(
        id = FUNC_ADJUST_MEDIA_VOLUME,
        packageName = SETTINGS_PACKAGE,
        serviceName = SETTINGS_SERVICE,
        description = "调节媒体音量（action: volume_up 调高 / volume_down 调低；step 可选，每次调节的音量百分比）",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "action": { "type": "string", "description": "音量调节方向：volume_up（调高）/ volume_down（调低）" },
                "step": { "type": "integer", "description": "每次调节的音量百分比，默认 10，范围 1~100" }
              },
              "required": ["action"]
            }
        """.trimIndent(),
        requiredParams = listOf("action"),
    )

    /**
     * 开启 / 关闭深色主题（参数 action：on 开启 / off 关闭），通过
     * UiModeManager#setNightModeActivated(boolean) 切换，行为与
     * DarkModeActivationPreferenceController 一致。返回结果带 action / enabled。
     * 功能定义见 Settings 的 fdesettings_appfunctions.xml 与
     * OpenSettingsPageAppFunctionService.setDarkTheme。
     */
    val SET_DARK_THEME = AppFunction(
        id = FUNC_SET_DARK_THEME,
        packageName = SETTINGS_PACKAGE,
        serviceName = SETTINGS_SERVICE,
        description = "开启/关闭深色主题（action: on 开启深色模式 / off 关闭深色模式）",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "action": { "type": "string", "description": "深色主题开关：on（开启）/ off（关闭）" }
              },
              "required": ["action"]
            }
        """.trimIndent(),
        requiredParams = listOf("action"),
    )

    /**
     * 按包名或应用名打开某个应用（packageName 或 appName 至少提供一个，同时提供时以 packageName
     * 优先）：packageName 直接 getLaunchIntentForPackage；appName 对所有可启动应用做忽略大小写的
     * 精确 / 包含匹配，命中唯一应用后启动。成功后返回结果带解析出的 packageName / appName。
     * 功能定义见 Settings 的 fdesettings_appfunctions.xml 与
     * OpenSettingsPageAppFunctionService.openApp。
     */
    val OPEN_APP = AppFunction(
        id = FUNC_OPEN_APP,
        packageName = SETTINGS_PACKAGE,
        serviceName = SETTINGS_SERVICE,
        description = "按包名或应用名打开某个应用（packageName 或 appName，至少提供一个，同时提供时以 packageName 优先）",
        schemaJson = """
            {
              "type": "object",
              "properties": {
                "packageName": { "type": "string", "description": "要打开应用的包名，例如 com.android.settings" },
                "appName": { "type": "string", "description": "要打开应用的名称（label），例如“设置”或“相册”，按应用名匹配" }
              },
              "required": []
            }
        """.trimIndent(),
        requiredParams = listOf(),
    )

    val ALL: List<AppFunction> = listOf(
        OPEN_IMAGE, PLAY_VIDEO, EDIT_IMAGE,
        OPEN_SETTINGS_PAGE, ADJUST_BRIGHTNESS,
        ADJUST_MEDIA_VOLUME, SET_DARK_THEME, OPEN_APP,
    )

    private val byId: Map<String, AppFunction> = ALL.associateBy { it.id }

    fun get(id: String): AppFunction? = byId[id]
}
