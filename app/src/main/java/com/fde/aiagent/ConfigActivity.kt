package com.fde.aiagent

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.fde.aiagent.ai.AiProvider

/**
 * API Key 配置页 —— 集中管理 AI 连接相关的敏感配置：
 *  - 供应商（Provider）、API Key、模型（Model）、Base URL；
 *  - 持久化到与首页共享的 SharedPreferences（fde_ai_agent），
 *    首页（[MainActivity]）在运行智能体 / 展示 model 信息时直接读取。
 *
 * 首页右上角提供「⚙ 配置」入口进入本页。
 */
class ConfigActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var spProvider: Spinner
    private lateinit var editApiKey: EditText
    private lateinit var editModel: EditText
    private lateinit var editBaseUrl: EditText
    private lateinit var lblBaseUrl: TextView

    companion object {
        private const val PREFS = "fde_ai_agent"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        spProvider = findViewById(R.id.spProvider)
        editApiKey = findViewById(R.id.editApiKey)
        editModel = findViewById(R.id.editModel)
        editBaseUrl = findViewById(R.id.editBaseUrl)
        lblBaseUrl = findViewById(R.id.lblBaseUrl)

        // AI 供应商选择：几套预设 + 自定义
        val providers = AiProvider.entries
        spProvider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providers.map { getString(it.labelRes) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 恢复上次保存的配置
        val savedProvider = prefs.getString(KEY_PROVIDER, AiProvider.DEEPSEEK.id)!!
        spProvider.setSelection(AiProvider.indexOf(savedProvider).coerceAtLeast(0))
        editApiKey.setText(prefs.getString(KEY_API, ""))
        editModel.setText(prefs.getString(KEY_MODEL, ""))
        editBaseUrl.setText(prefs.getString(KEY_BASE_URL, ""))

        // 切换供应商即保存，并同步 Base URL 字段可见性（自定义时才显示）
        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                save()
                onProviderChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                onProviderChanged()
            }
        }

        findViewById<Button>(R.id.btnConfigSave).setOnClickListener {
            save()
            toast(getString(R.string.config_saved))
            finish()
        }

        findViewById<Button>(R.id.btnConfigCancel).setOnClickListener {
            finish()
        }

        onProviderChanged() // 初次渲染（自定义时才显示 Base URL 字段）
    }

    /** 切换供应商时：Base URL 仅在选择「自定义」时显示。 */
    private fun onProviderChanged() {
//        val isCustom = AiProvider.entries
//            .getOrNull(spProvider.selectedItemPosition) == AiProvider.CUSTOM
//        lblBaseUrl.visibility = if (isCustom) View.VISIBLE else View.GONE
//        editBaseUrl.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    private fun selectedProvider(): AiProvider =
        AiProvider.entries.getOrNull(spProvider.selectedItemPosition) ?: AiProvider.DEEPSEEK

    /** 保存到共享配置（首页运行智能体时直接读取）。 */
    private fun save() {
        prefs.edit()
            .putString(KEY_PROVIDER, selectedProvider().id)
            .putString(KEY_API, editApiKey.text.toString().trim())
            .putString(KEY_MODEL, editModel.text.toString().trim())
            .putString(KEY_BASE_URL, editBaseUrl.text.toString().trim())
            .apply()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
