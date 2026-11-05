package io.github.yuxiximyxx.navwake

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.yuxiximyxx.navwake.config.AppTargets
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.config.Packages

/**
 * 模块设置页。
 *
 * 三件事：
 * 1. 总开关；
 * 2. 默认目标（点选 / 清除，清除后每次长按都弹选择框）；
 * 3. 诊断（探针模式、日志位置）。
 *
 * 纯 Android View 手搓，不引 Compose —— 依赖越少构建越稳。
 */
class MainActivity : Activity() {

    private lateinit var body: LinearLayout
    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavConfig.bind(getSharedPreferences(NavConfig.PREFS_NAME, MODE_PRIVATE))

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        setContentView(ScrollView(this).apply {
            addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        rebuild()
    }

    private fun rebuild() {
        body.removeAllViews()

        body.addView(title("导航条唤醒重定向"))
        body.addView(sub("长按导航条 → 唤起自选 AI（Muse / Grok Bot / DSH Go）"))

        // ── 总开关 ────────────────────────────────────────────────
        body.addView(section("总开关"))
        body.addView(Switch(this).apply {
            text = "启用重定向"
            isChecked = NavConfig.enabled()
            setOnCheckedChangeListener { _, checked ->
                NavConfig.setEnabled(checked)
                rebuild()
            }
        })
        body.addView(note("关闭后完全恢复原 YOYO 行为，可随时打开。"))

        // ── 可唤起的应用 ──────────────────────────────────────────
        body.addView(section("可唤起的应用"))
        val configured = AppTargets.all(this)
        body.addView(note(
            if (configured.isEmpty()) "还没选应用 —— 点下面「选择应用」勾选"
            else "已选 ${configured.size} 个：" +
                configured.joinToString("、") { it.displayName } +
                if (configured.size > AppTargets.installed(this).size) "（其中部分未安装）" else ""
        ))
        body.addView(Button(this).apply {
            text = "选择应用"
            setOnClickListener { showAppPicker() }
        })

        // ── 默认目标 ──────────────────────────────────────────────
        body.addView(section("默认目标"))
        val installed = AppTargets.installed(this)
        val currentDefault = NavConfig.defaultTarget()

        body.addView(note(
            if (currentDefault.isEmpty()) "当前：每次长按都弹选择框"
            else "当前：直接打开 ${AppTargets.labelOf(this, currentDefault)}"
        ))

        installed.forEach { target ->
            val isDefault = target.pkg == currentDefault
            body.addView(Button(this).apply {
                text = (if (isDefault) "● " else "○ ") + target.displayName
                setOnClickListener {
                    NavConfig.setDefaultTarget(target.pkg)
                    rebuild()
                }
            })
        }

        body.addView(Button(this).apply {
            text = "清除默认（回到每次都弹框）"
            isEnabled = currentDefault.isNotEmpty()
            setOnClickListener {
                NavConfig.clearDefaultTarget()
                rebuild()
            }
        })

        // ── 拦截说明 ──────────────────────────────────────────────
        body.addView(section("拦截的是什么"))
        body.addView(body2(
            "真机实测：长按导航条时，SystemUI 会调\n" +
                "com.hihonor.magicvoice/.startup.StartAssistantService\n" +
                "拉起 YOYO 的悬浮窗。模块只在 SystemUI 进程里拦这一条调用，\n" +
                "命中后改为打开你选的应用。\n\n" +
                "任何异常（目标被卸载、选择框起不来、hook 失败）都会放行原行为，\n" +
                "长按绝不会变成死键。锁屏状态下不拦截。"
        ))

        // ── 诊断 ──────────────────────────────────────────────────
        body.addView(section("诊断"))
        body.addView(Switch(this).apply {
            text = "探针模式（阶段 0 日志，平时不用开）"
            isChecked = NavConfig.probeMode()
            setOnCheckedChangeListener { _, checked ->
                NavConfig.setProbeMode(checked)
            }
        })

        body.addView(body2("作用域：\n" + Packages.ALL.joinToString("\n") { "· $it" }))
        body.addView(body2("日志：/data/adb/lspd/log/modules_*.log\n（不是 logcat）"))
        body.addView(Button(this).apply {
            text = "复制日志路径"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("log path", LOG_PATH))
                (it as Button).text = "已复制 ✓"
            }
        })
    }

    /** 多选对话框：列出本机所有可启动应用，勾选允许被唤起的那些。 */
    private fun showAppPicker() {
        val apps = AppTargets.launchableApps(this, packageName)
        if (apps.isEmpty()) {
            toast("没有可选的应用")
            return
        }
        val selected = AppTargets.selectedPackages().toMutableSet()
        val labels = apps.map { it.label }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].pkg in selected }

        AlertDialog.Builder(this)
            .setTitle("选择可唤起的应用")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val picked = apps.filterIndexed { index, _ -> checked[index] }.map { it.pkg }
                AppTargets.saveSelectedPackages(this, picked)
                rebuild()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(getColor(R.color.nw_text_primary))
    }

    private fun sub(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.nw_accent))
        setPadding(0, dp(4), 0, 0)
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.START
        setTextColor(getColor(R.color.nw_text_primary))
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.nw_text_secondary))
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun body2(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(0f, 1.3f)
        setTextColor(getColor(R.color.nw_text_secondary))
        setPadding(0, dp(4), 0, dp(4))
    }

    private companion object {
        const val LOG_PATH = "/data/adb/lspd/log/"
    }
}
