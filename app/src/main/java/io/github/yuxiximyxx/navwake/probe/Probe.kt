package io.github.yuxiximyxx.navwake.probe

import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.hook.HookEntry

/**
 * 探针基类 —— 阶段 0 的每一个探针都只做「观察」，绝不允许改变原逻辑。
 *
 * 三条铁律（写在这里，改代码的人先看这里）：
 * 1. 安装失败只记日志，返回 false，不影响其它探针；
 * 2. 回调里任何异常都吞掉并 `chain.proceed()`，绝不让 SystemUI 崩；
 * 3. 不修改返回值、不吞掉参数、不跳过原逻辑 —— 阶段 0 只打日志。
 */
abstract class Probe {

    abstract val id: String

    /** 作用域提示：这个探针该装在哪些包里。 */
    abstract val forPackages: Set<String>

    abstract fun install(context: HookContext)

    protected fun installed(context: HookContext) {
        ModuleLog.i(HookEntry.TAG, "探针就绪 [$id] pkg=${context.packageName}")
    }
}

/**
 * 监控名单：只有和「导航条 / YOYO / 助理」有关的调用才值得打日志，
 * 否则 SystemUI 每秒几百次 startActivity 会把日志冲爆，反而找不到证据。
 */
object Watch {

    private val PACKAGE_HINTS = listOf(
        "magicvoice",
        "hihonor",
        "yoyo",
        "assist",
        "voice"
    )

    private val ACTION_HINTS = listOf(
        "ASSIST",
        "VOICE",
        "YOYO",
        "SEARCH",
        "NAVIGATION",
        "WAKEWORD"
    )

    /** 命中监控名单返回命中的关键词，否则 null。 */
    fun match(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase()
        PACKAGE_HINTS.firstOrNull { lower.contains(it) }?.let { return "pkg:$it" }
        val upper = text.uppercase()
        ACTION_HINTS.firstOrNull { upper.contains(it) }?.let { return "action:$it" }
        return null
    }

    /** 从 Intent 里抽出值得看的信息，用于日志行。 */
    fun describeIntent(intent: Any?): String {
        if (intent == null) return "null"
        val clazz = intent.javaClass
        fun field(name: String): String? = runCatching {
            clazz.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
                .let { it.invoke(intent)?.toString() }
        }.getOrNull()

        val action = runCatching { clazz.getMethod("getAction").invoke(intent) as? String }.getOrNull()
        val data = runCatching { clazz.getMethod("getDataString").invoke(intent) as? String }.getOrNull()
        val component = runCatching { clazz.getMethod("getComponent").invoke(intent)?.toString() }
            .getOrNull()
        val packageName = runCatching { clazz.getMethod("getPackage").invoke(intent) as? String }
            .getOrNull()
        val flags = runCatching { clazz.getMethod("getFlags").invoke(intent)?.toString() }
            .getOrNull()
        val extras = runCatching {
            val bundle = clazz.getMethod("getExtras").invoke(intent) ?: return@runCatching "-"
            val keySet = runCatching {
                bundle.javaClass.getMethod("keySet").invoke(bundle) as? Set<*>
            }.getOrNull()
            keySet?.joinToString(",") { key ->
                val value = runCatching {
                    bundle.javaClass.getMethod("get", String::class.java).invoke(bundle, key)
                }.getOrNull()
                "$key=$value"
            } ?: "-"
        }.getOrNull() ?: "-"
        val selector = runCatching { field("Selector") }.getOrNull()

        return buildString {
            append("action=").append(action)
            append(" data=").append(data)
            append(" pkg=").append(packageName)
            append(" component=").append(component)
            append(" flags=").append(flags)
            append(" extras={").append(extras).append("}")
            if (selector != null) append(" selector=").append(selector)
        }
    }

    /**
     * 打一段**裁剪过的**调用栈：从本项目之外的第一帧开始，最多 [frames] 帧。
     * 目的是让人一眼看到「SystemUI 的哪个类调的」，而不是看一堆 Handler 内部帧。
     */
    fun stack(maxFrames: Int = 12, skipPackages: List<String> = listOf("io.github.yuxiximyxx", "java.lang.reflect", "io.github.libxposed")): String {
        val trace = Throwable().stackTrace
            .filterNot { frame -> skipPackages.any { frame.className.startsWith(it) } }
        return trace.take(maxFrames)
            .map { "    at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            .joinToString("\n")
    }
}
