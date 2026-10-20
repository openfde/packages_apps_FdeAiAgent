
package com.fde.aiagent;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.Toolbar;
import android.view.View;
import com.fde.aiagent.appfunctions.AgentRunner;
import com.fde.aiagent.appfunctions.GalleryAppFunctionsClient;

/**
 * FDE AI Agent — DeepSeek key configuration + AppFunctions demo.
 *
 *  - 输入并保存自己的 DeepSeek API Key（SharedPreferences）。
 *  - 「运行智能体」：把提示词交给 DeepSeek，模型选择工具后由本应用通过平台
 *    AppFunctionManager 跨应用调用 com.fde.gallery3d 的 AppFunction（openImage / playVideo）。
 *  - 「打开相册」：不经过模型，直接跨应用调用
 *    com.fde.gallery3d.appfunctions.MediaAppFunctionsService#openImage，等价于
 *    cmd app_function execute-app-function --package com.fde.gallery3d \
 *        --function '...MediaAppFunctionsService#openImage' \
 *        --parameters '{"path": "...", "title": "..."}'
 */
public class MainActivity extends Activity {
    private static final String TAG = "FdeAiAgent";

    private static final String PREFS = "fde_ai_agent";
    private static final String KEY_API = "sk-3f6a5973c15e4ad79a020b3bd44150eb";
    private static final String KEY_PATH = "image_path";
    private static final String KEY_PROMPT = "prompt";

    private EditText editKey;
    private EditText editPath;
    private EditText editPrompt;
    private TextView tvLog;
    private ScrollView svLog;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getActionBar() != null) {
            getActionBar().hide();
        }

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        editKey = findViewById(R.id.editDeepSeekKey);
        editPath = findViewById(R.id.editImagePath);
        editPrompt = findViewById(R.id.editAgentPrompt);
        tvLog = findViewById(R.id.tvAgentLog);
        svLog = findViewById(R.id.svLog);

        // 直接从 SharedPreferences 恢复上次保存的配置（自配置 DeepSeek Key 等）。
        editKey.setText(prefs.getString(KEY_API, KEY_API));
        editPath.setText(prefs.getString(KEY_PATH, GalleryAppFunctionsClient.DEFAULT_IMAGE_PATH));
        editPrompt.setText(prefs.getString(KEY_PROMPT, "打开 /mnt/sdcard/Pictures/aa.png 这张图片预览"));

        Button btnRun = findViewById(R.id.btnRunAgent);
        Button btnOpen = findViewById(R.id.btnOpenGallery);
        Button btnPermission = findViewById(R.id.btnGrantFunctionPermission);
        Button btnClear = findViewById(R.id.btnClearLog);

        btnRun.setOnClickListener(v -> runAgent());
        btnOpen.setOnClickListener(v -> openImageDirect());
        btnPermission.setOnClickListener(v -> showPermissionStatus());
        btnClear.setOnClickListener(v -> tvLog.setText(""));
    }

    private void runAgent() {
        String key = editKey.getText().toString().trim();
        String prompt = editPrompt.getText().toString().trim();
        saveSettings();

        if (key.isEmpty()) {
            toast("请先填写并保存 DeepSeek API Key");
            return;
        }
        if (prompt.isEmpty()) {
            prompt = "打开 /mnt/sdcard/Pictures/aa.png 这张图片预览";
        }

        appendLog("===== 运行智能体 =====");
        AgentRunner.INSTANCE.runAgent(
                getApplicationContext(),
                key,
                prompt,
                this::appendLog,      // 实时日志
                this::appendLog);     // 最终答案
    }

    /** 不经过 DeepSeek，直接跨应用调用相册的 openImage AppFunction。 */
    private void openImageDirect() {
        saveSettings();
        String path = editPath.getText().toString().trim();
        if (path.isEmpty()) {
            toast("请填写图片路径");
            return;
        }
        String title = defaultTitle(path);
        appendLog("===== 直接调用相册 AppFunction: openImage =====");
        appendLog("target  = com.fde.gallery3d");
        appendLog("service = com.fde.gallery3d.appfunctions.MediaAppFunctionsService");
        appendLog("params  = {\"path\": \"" + path + "\", \"title\": \"" + title + "\"}");
        AgentRunner.INSTANCE.openImage(getApplicationContext(), path, title, this::appendLog);
    }

    private void showPermissionStatus() {
        GalleryAppFunctionsClient client = new GalleryAppFunctionsClient(getApplicationContext());
        StringBuilder sb = new StringBuilder();
        sb.append("AppFunctions 支持: ").append(client.isSupported()).append('\n');
        sb.append("EXECUTE_APP_FUNCTIONS: ").append(client.hasExecutePermission() ? "已授予" : "未授予").append('\n');
        if (!client.hasExecutePermission()) {
            sb.append("提示：本应用需以 privileged + platform 签名装入 system_ext 才会被授予该权限。");
        }
        appendLog(sb.toString());
    }

    private void saveSettings() {
        prefs.edit()
                .putString(KEY_API, editKey.getText().toString().trim())
                .putString(KEY_PATH, editPath.getText().toString().trim())
                .putString(KEY_PROMPT, editPrompt.getText().toString().trim())
                .apply();
    }

    private String defaultTitle(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void appendLog(final String line) {
        runOnUiThread(() -> {
            String prev = tvLog.getText().toString();
            tvLog.setText(prev + (prev.endsWith("\n") || prev.isEmpty() ? "" : "\n") + line);
            // 控制台自动滚动到底部（PC 终端习惯）
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
