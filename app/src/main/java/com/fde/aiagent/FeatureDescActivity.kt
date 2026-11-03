package com.fde.aiagent

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/**
 * 功能说明页 —— 用独立的 Activity 展示本智能体的功能总览与使用方法。
 *
 * 首页（[MainActivity]）「功能说明」按钮进入本页，不再把长文本塞进运行日志面板。
 * 每个功能介绍后都会独占一行/单独换行展示（feature_summary 中每个 □ 功能块之间用空行分隔），
 * 便于阅读。所有文案来自 @string/…，中英文 + 深色模式自动适配。
 *
 * 本 Activity 只负责页面展示与返回，不做任何业务逻辑。
 */
class FeatureDescActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feature_desc)

        findViewById<Button>(R.id.btnFeatureDescBack).setOnClickListener {
            finish()
        }
    }
}
