package io.github.yuxiximyxx.navwake

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.yuxiximyxx.navwake.config.AppTargets
import io.github.yuxiximyxx.navwake.config.NavConfig

/**
 * ChooserActivity —— 长按导航条后弹出的底部选择框。
 *
 * 由 SystemUI 进程直接拉起（跨包 startActivity），所以：
 * - `exported="true"`；
 * - 主题半透明、贴近底部，看起来像浮层；
 * - `excludeFromRecents` + `noHistory`，不污染任务列表。
 *
 * 点谁开谁；勾了「设为默认」就记住，下次直达。
 */
class ChooserActivity : Activity() {

    private lateinit var rememberCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavConfig.bind(getSharedPreferences(NavConfig.PREFS_NAME, MODE_PRIVATE))

        val targets = AppTargets.installed(this)
        if (targets.isEmpty()) {
            // 极端情况：三个都卸载了。直接关掉，不拦任何东西。
            finish()
            return
        }

        // 兜底直达：hook 侧的远程 prefs 有缓存，刚改完默认目标时可能还没同步。
        // 那一份是陈旧的，就会照样把选择框拉起来；这里用**本地**（永远最新）再判一次，
        // 设过默认就立刻开目标应用并退出，用户看不到这一下闪动。
        val defaultTarget = targets.firstOrNull { it.pkg == NavConfig.defaultTarget() }
        if (defaultTarget != null && targets.any { it.pkg == defaultTarget.pkg }) {
            if (AppTargets.launch(this, defaultTarget)) {
                finish()
                return
            }
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(getColor(R.color.nw_surface))
            }
        }

        card.addView(TextView(this).apply {
            text = "长按唤醒谁？"
            textSize = 15f
            setTextColor(getColor(R.color.nw_text_primary))
            setPadding(0, 0, 0, dp(8))
        })

        targets.forEach { target ->
            card.addView(makeRow(target.displayName, target.pkg, density) {
                if (rememberCheck.isChecked) NavConfig.setDefaultTarget(target.pkg)
                AppTargets.launch(this, target)
                finish()
            })
        }

        rememberCheck = CheckBox(this).apply {
            text = "设为默认，下次直达"
            textSize = 13f
            setTextColor(getColor(R.color.nw_text_secondary))
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(rememberCheck)

        card.addView(TextView(this).apply {
            text = "取消"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.nw_text_secondary))
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener { finish() }
        })

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { finish() } // 点空白处关掉
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        setContentView(container)
    }

    private fun makeRow(
        title: String,
        packageName: String,
        density: Float,
        onClick: () -> Unit
    ): View {
        fun dp(value: Int) = (value * density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }

        val icon = runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull()
        if (icon != null) {
            row.addView(ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    marginEnd = dp(12)
                }
            })
        }

        row.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(getColor(R.color.nw_text_primary))
        })

        return row
    }
}
