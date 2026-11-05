package io.github.yuxiximyxx.navwake.config

import android.content.SharedPreferences

/**
 * NavConfig —— 设置侧与 hook 侧共用的配置入口。
 *
 * 两份写、读一份：
 * - 设置页写模块自己的 SharedPreferences（权威来源）；
 * - hook 侧用 LSPosed 的 `getRemotePreferences()` 读同一份，改完即时生效。
 *
 * 所有读取都带默认值，prefs 没绑上也不会崩。
 */
object NavConfig {

    const val PREFS_NAME = "navwake_settings"

    private const val KEY_ENABLED = "redirect_enabled"
    private const val KEY_DEFAULT_TARGET = "default_target"
    private const val KEY_SELECTED = "selected_packages"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_PROBE_MODE = "probe_mode"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun bind(value: SharedPreferences?) {
        prefs = value
    }

    /** 重新取一次远程 prefs（绕过进程内缓存）。失败保留原值。 */
    fun refresh(provider: (() -> SharedPreferences?)?) {
        if (provider == null) return
        runCatching { provider() }.getOrNull()?.let { prefs = it }
    }

    /** 拿到可写的 prefs（设置页用）。 */
    fun writable(): SharedPreferences? = prefs

    // ── 功能开关 ────────────────────────────────────────────────────

    /** 总开关：关掉就完全恢复原 YOYO 行为。默认开。 */
    fun enabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, true) ?: true

    fun setEnabled(value: Boolean) {
        write { it.putBoolean(KEY_ENABLED, value) }
    }

    // ── 默认目标 ────────────────────────────────────────────────────

    /** 默认目标包名；空串 = 每次都弹选择框。 */
    fun defaultTarget(): String = prefs?.getString(KEY_DEFAULT_TARGET, "") ?: ""

    fun setDefaultTarget(pkg: String) {
        write { it.putString(KEY_DEFAULT_TARGET, pkg) }
    }

    fun clearDefaultTarget() {
        write { it.putString(KEY_DEFAULT_TARGET, "") }
    }

    // ── 已选目标 ────────────────────────────────────────────────────

    /** 用户在设置页勾选、允许被唤起的应用包名（有序）。 */
    fun selectedPackages(): List<String> =
        prefs?.getStringSet(KEY_SELECTED, emptySet())?.sorted() ?: emptyList()

    fun setSelectedPackages(packages: List<String>) {
        write { it.putStringSet(KEY_SELECTED, packages.toSet()) }
    }

    // ── 诊断 ────────────────────────────────────────────────────────

    /** 0 = 只看关键信息，1 = 常规（默认），2 = 全量。 */
    fun logLevel(): Int = prefs?.getInt(KEY_LOG_LEVEL, 1) ?: 1

    fun setLogLevel(value: Int) {
        write { it.putInt(KEY_LOG_LEVEL, value.coerceIn(0, 2)) }
    }

    /**
     * 阶段 0 的探针模式。
     *
     * 默认**关闭**：阶段 1 只装重定向 hook，不装探针，避免多挂点位增加 SystemUI 风险。
     * 需要重新定位链路时再打开。
     */
    fun probeMode(): Boolean = prefs?.getBoolean(KEY_PROBE_MODE, false) ?: false

    fun setProbeMode(value: Boolean) {
        write { it.putBoolean(KEY_PROBE_MODE, value) }
    }


    /**
     * 写配置：**本地一份 + 远程一份**。
     *
     * 本地那份是权威来源（hook 进程读的就是这个文件）；
     * 远程那份走 LSPosed 服务，作用是让已注入的 SystemUI 立刻丢掉缓存。
     * 只写本地的话，真机上要等缓存过期才生效（踩过）。
     */
    private fun write(block: (SharedPreferences.Editor) -> Unit) {
        prefs?.let { runCatching { it.edit().also(block).apply() } }
        ServiceBridge.pushRemote(PREFS_NAME, block)
    }

    // ── 阶段 0 探针细项（只在 probeMode 打开时有意义） ───────────────

    fun probeAssist(): Boolean = prefs?.getBoolean("probe_assist", true) ?: true

    fun probeStartActivity(): Boolean =
        prefs?.getBoolean("probe_start_activity", true) ?: true

    fun probeBrain(): Boolean = prefs?.getBoolean("probe_brain", true) ?: true

    fun probeMagicVoiceActivity(): Boolean =
        prefs?.getBoolean("probe_magicvoice_activity", true) ?: true
}
