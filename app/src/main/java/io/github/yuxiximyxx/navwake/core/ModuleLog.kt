package io.github.yuxiximyxx.navwake.core

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * ModuleLog —— 统一日志出口。
 *
 * 两路并行：
 * 1. `module.log()`：唯一可靠进入 LSPosed 日志模块的通道（实测在被注入的进程里
 *    android.util.Log 有时完全写不进 logcat 缓冲）。
 * 2. android.util.Log：方便 adb logcat 现场排查。
 *
 * 级别由设置页的「日志级别」控制，但 ERROR 永远打 —— 熔断现场的日志不能被用户误关。
 */
object ModuleLog {
    private const val TAG = "NavWake"

    @Volatile
    var module: XposedModule? = null

    @Volatile
    private var level: Int = 1

    /** 当前是否全量级别（2）—— 阶段 0 定位时靠它决定要不要打调用栈。 */
    val isVerbose: Boolean
        get() = level >= 2

    fun setLevel(value: Int) {
        level = value.coerceIn(0, 2)
    }

    fun v(tag: String = TAG, message: String) {
        emit(Log.VERBOSE, 2, tag, message, null)
    }

    fun d(tag: String = TAG, message: String) {
        emit(Log.DEBUG, 1, tag, message, null)
    }

    fun i(tag: String = TAG, message: String) {
        emit(Log.INFO, 0, tag, message, null)
    }

    fun w(tag: String = TAG, message: String, error: Throwable? = null) {
        emit(Log.WARN, 0, tag, message, error)
    }

    fun e(tag: String = TAG, message: String, error: Throwable? = null) {
        emit(Log.ERROR, 0, tag, message, error)
    }

    private fun emit(priority: Int, requiredLevel: Int, tag: String, message: String, error: Throwable?) {
        val allowed = priority == Log.ERROR || level >= requiredLevel
        if (!allowed) return
        runCatching { Log.println(priority, "$TAG/$tag", message) }
        if (error != null) runCatching { Log.println(Log.ERROR, "$TAG/$tag", Log.getStackTraceString(error)) }
        runCatching {
            module?.log(priority, "$TAG/$tag", message, error)
        }
    }
}
