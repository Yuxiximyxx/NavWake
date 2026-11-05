package io.github.yuxiximyxx.navwake.probe

import android.app.Activity
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.yuxiximyxx.navwake.core.argOrNull
import io.github.yuxiximyxx.navwake.core.thisOrNull
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.hook.HookEntry

/**
 * MagicVoiceActivityProbe —— 探针⑤：YOYO 侧的**入站**入口。
 *
 * 如果 SystemUI 侧只发了个 stub、真正带参数拉起在 magicvoice 里，那这里能看到：
 * - 到底是哪个 Activity 站起来（`onCreate` / `onNewIntent`）
 * - 带进来的 Intent 组件、action、extras（需求单要求「extras 全打出来」）
 * - referrer 与调用栈（谁发起的）
 *
 * hook 的是 `android.app.Activity` 基类方法 —— 进程内所有 Activity 都会经过这里，
 * 不用预先知道荣耀的类名，也不违反「不瞎通配系统类」的红线（这是框架基类，语义确定）。
 *
 * ⚠️ 只观察：原样 proceed()，不改 Intent。
 */
object MagicVoiceActivityProbe : Probe() {

    override val id = "magicvoice-activity"

    override val forPackages: Set<String> = setOf(HookEntry.MAGICVOICE)

    override fun install(context: HookContext) {
        if (!NavConfig.probeMagicVoiceActivity()) {
            ModuleLog.i(HookEntry.TAG, "探针 [$id] 被设置关闭，跳过")
            return
        }

        val clazz = context.findClass("android.app.Activity") ?: run {
            ModuleLog.w(HookEntry.TAG, "探针 [$id] 找不到 android.app.Activity，跳过")
            return
        }

        var hit = 0
        hit += context.hookAllMethodsNamed(
            clazz = clazz,
            name = "onCreate",
            idPrefix = "$id#onCreate"
        ) { chain -> observe("onCreate", chain) }

        hit += context.hookAllMethodsNamed(
            clazz = clazz,
            name = "onNewIntent",
            idPrefix = "$id#onNewIntent"
        ) { chain -> observe("onNewIntent", chain) }

        hit += context.hookAllMethodsNamed(
            clazz = clazz,
            name = "onResume",
            idPrefix = "$id#onResume"
        ) { chain -> observe("onResume", chain) }

        ModuleLog.i(HookEntry.TAG, "探针 [$id] 在 magicvoice 挂了 $hit 个方法")
        installed(context)
    }

    private fun observe(methodName: String, chain: XposedInterface.Chain): Any? {
        runCatching {
            val activity = chain.thisOrNull() as? Activity ?: return@runCatching
            val intent: Intent? = when (methodName) {
                "onNewIntent" -> chain.argOrNull(0) as? Intent ?: activity.intent
                else -> activity.intent
            }
            // onResume 在每个页面都会触发，只保留「和助理有关」的那些，避免把日志冲爆
            if (methodName == "onResume") {
                val hit = Watch.match(intent?.component?.className)
                    ?: Watch.match(intent?.action)
                    ?: Watch.match(intent?.`package`)
                if (hit == null) return@runCatching
            }

            val referrer = runCatching {
                activity.javaClass.getMethod("getReferrer").invoke(activity)?.toString()
            }.getOrNull()

            ModuleLog.i(
                HookEntry.TAG,
                "★★ MAGICVOICE 入站 $methodName 类=${activity.javaClass.name}\n" +
                    "    " + Watch.describeIntent(intent) + "\n" +
                    "    referrer=" + referrer + "\n" +
                    Watch.stack(maxFrames = 12)
            )
        }
        return chain.proceed()
    }
}
