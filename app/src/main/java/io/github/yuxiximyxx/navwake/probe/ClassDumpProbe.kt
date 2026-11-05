package io.github.yuxiximyxx.navwake.probe

import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.hook.HookEntry

/**
 * ClassDumpProbe —— 纯反射侦察：把可疑类的方法签名打进日志。
 *
 * 它**不 hook 任何东西**，只读 `declaredMethods`，零行为影响。
 * 价值：荣耀改了类名/方法名时，靠它把能用的点位直接摊在日志里，
 * 比盲猜 hook 点位安全得多（也遵守「不许大面积通配 hook」的红线）。
 */
object ClassDumpProbe {

    /**
     * 列出 [className] 上名字包含任一 [keywords] 的方法（含参数类型），打日志。
     *
     * @return 命中的方法数量
     */
    fun dumpMethods(
        context: HookContext,
        className: String,
        keywords: List<String>,
        tagHint: String = ""
    ): Int {
        val clazz = context.findClass(className) ?: return 0
        val matched = runCatching {
            clazz.declaredMethods.filter { method ->
                keywords.any { keyword -> method.name.contains(keyword, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())

        if (matched.isEmpty()) {
            ModuleLog.d(
                HookEntry.TAG,
                "[dump:$tagHint] $className 没有名字含 $keywords 的方法"
            )
            return 0
        }

        matched.forEach { method ->
            val params = method.parameterTypes.joinToString(", ") { it.simpleName }
            ModuleLog.i(
                HookEntry.TAG,
                "[dump:$tagHint] $className#${method.name}($params) -> ${method.returnType.simpleName}"
            )
        }
        return matched.size
    }

    /**
     * 列出某个类上**所有**方法名（仅名字，不出参数）。类很大时只打前 [limit] 个，
     * 用于「知道类存在但不知道方法叫啥」时摸形状。
     */
    fun dumpAllMethodNames(context: HookContext, className: String, limit: Int = 80) {
        val clazz = context.findClass(className) ?: run {
            ModuleLog.d(HookEntry.TAG, "[dump] 类不存在: $className")
            return
        }
        val names = runCatching {
            clazz.declaredMethods.map { it.name }.distinct().sorted()
        }.getOrOrDefault(emptyList())

        ModuleLog.i(
            HookEntry.TAG,
            "[dump] $className 共 ${names.size} 个不同方法名" +
                if (names.size > limit) "（只打前 $limit 个）" else ""
        )
        names.take(limit).chunked(6).forEach { chunk ->
            ModuleLog.i(HookEntry.TAG, "[dump]   " + chunk.joinToString("  "))
        }
    }

    private fun <T> Result<T>.getOrOrDefault(default: T): T = getOrNull() ?: default
}
