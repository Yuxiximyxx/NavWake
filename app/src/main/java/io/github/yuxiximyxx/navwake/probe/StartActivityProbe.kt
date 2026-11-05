package io.github.yuxiximyxx.navwake.probe

import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.yuxiximyxx.navwake.core.argOrNull
import io.github.yuxiximyxx.navwake.core.thisOrNull
import io.github.yuxiximyxx.navwake.core.argsArray
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.hook.HookEntry

/**
 * StartActivityProbe —— 探针②：**进程内出站**的所有拉起动作。
 *
 * 这是最能出证据的一个：
 * 长按导航条时，SystemUI 最终一定要拉起 YOYO。只要 hook 住本进程内的
 * `ContextImpl.startActivity*`，把目标匹配 magicvoice 的调用连同**调用栈**打出来，
 * 就能直接从栈里读到 SystemUI 侧的真实类名 + 方法名（对应需求单 Q1）。
 *
 * 为什么是 ContextImpl：它是进程内真正执行 `startActivity` 的地方，
 * 而 `ActivityTaskManager` 在 system_server —— MagicOS 上该作用域会被 LSPosed
 * 静默跳过（见 docs/scaffold-facts.md §8），挂那里等于没挂。
 *
 * ⚠️ 只观察：原样 `proceed()`，不改 Intent、不吞参数。
 */
object StartActivityProbe : Probe() {

    override val id = "start-activity"

    override val forPackages: Set<String> = HookEntry.SYSTEMUI_PACKAGES + setOf(HookEntry.MAGICVOICE)

    /** 这些方法名都算「进程内出站拉起」，全部挂上做观察。 */
    private val methodNames = listOf(
        "startActivity",
        "startActivityForResult",
        "startActivityAsUser",
        "startActivityAsCaller"
    )

    override fun install(context: HookContext) {
        if (!NavConfig.probeStartActivity()) {
            ModuleLog.i(HookEntry.TAG, "探针 [$id] 被设置关闭，跳过")
            return
        }

        val clazz = context.findClass("android.app.ContextImpl") ?: run {
            ModuleLog.w(HookEntry.TAG, "探针 [$id] 找不到 android.app.ContextImpl，跳过")
            return
        }

        var hit = 0
        for (methodName in methodNames) {
            // 只装参数不多的重载（Intent 打头那几个），避免把派生方法重复挂一遍
            hit += context.hookAllMethodsNamed(
                clazz = clazz,
                name = methodName,
                idPrefix = "$id#$methodName",
                filter = { method ->
                    method.parameterTypes.isNotEmpty() &&
                        Intent::class.java.isAssignableFrom(method.parameterTypes[0])
                }
            ) { chain -> observe(methodName, chain) }
        }

        ModuleLog.i(HookEntry.TAG, "探针 [$id] 在 ${context.packageName} 挂了 $hit 个方法")
        installed(context)
    }

    private fun observe(methodName: String, chain: XposedInterface.Chain): Any? {
        runCatching {
            val intent = chain.argOrNull(0) as? Intent
            if (intent != null) {
                val key = Watch.match(intent.component?.packageName)
                    ?: Watch.match(intent.component?.className)
                    ?: Watch.match(intent.`package`)
                    ?: Watch.match(intent.action)

                if (key != null) {
                    val caller = chain.thisOrNull()?.javaClass?.name
                    ModuleLog.i(
                        HookEntry.TAG,
                        "★★ START_ACTIVITY 命中[$key] ContextImpl.$methodName caller=$caller\n" +
                            "    " + Watch.describeIntent(intent) + "\n" +
                            Watch.stack(maxFrames = 16)
                    )
                }
            }
        }
        return chain.proceed()
    }
}

/**
 * IntentDispatchProbe —— 探针③：广播 / 服务 方向的拉起。
 *
 * 有些系统的助手拉起不走 Activity 而走 `sendBroadcast` / `startService`，
 * 或者 SystemUI 只负责发个广播、真正拉起在 magicvoice。
 * 这些条目只在命中监控名单时打日志，避免把 SystemUI 的日常流量冲进来。
 */
object IntentDispatchProbe : Probe() {

    override val id = "intent-dispatch"

    override val forPackages: Set<String> = HookEntry.SYSTEMUI_PACKAGES + setOf(HookEntry.MAGICVOICE)

    private val methodNames = listOf(
        "sendBroadcast",
        "sendOrderedBroadcast",
        "startService",
        "startForegroundService",
        "bindService",
        "sendBroadcastAsUser",
        "startServiceCommon"
    )

    override fun install(context: HookContext) {
        val clazz = context.findClass("android.app.ContextImpl") ?: run {
            ModuleLog.w(HookEntry.TAG, "探针 [$id] 找不到 android.app.ContextImpl，跳过")
            return
        }

        var hit = 0
        for (methodName in methodNames) {
            hit += context.hookAllMethodsNamed(
                clazz = clazz,
                name = methodName,
                idPrefix = "$id#$methodName",
                filter = { method ->
                    method.parameterTypes.isNotEmpty() &&
                        Intent::class.java.isAssignableFrom(method.parameterTypes[0])
                }
            ) { chain -> observe(methodName, chain) }
        }

        ModuleLog.i(HookEntry.TAG, "探针 [$id] 在 ${context.packageName} 挂了 $hit 个方法")
        installed(context)
    }

    private fun observe(methodName: String, chain: XposedInterface.Chain): Any? {
        runCatching {
            val intent = chain.argOrNull(0) as? Intent
            if (intent != null) {
                val key = Watch.match(intent.action)
                    ?: Watch.match(intent.component?.packageName)
                    ?: Watch.match(intent.component?.className)
                    ?: Watch.match(intent.`package`)
                if (key != null) {
                    ModuleLog.i(
                        HookEntry.TAG,
                        "★★ DISPATCH 命中[$key] ContextImpl.$methodName\n" +
                            "    " + Watch.describeIntent(intent) + "\n" +
                            Watch.stack(maxFrames = 14)
                    )
                }
            }
        }
        return chain.proceed()
    }
}
