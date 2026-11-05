package io.github.yuxiximyxx.navwake.probe

import io.github.libxposed.api.XposedInterface
import io.github.yuxiximyxx.navwake.core.argOrNull
import io.github.yuxiximyxx.navwake.core.thisOrNull
import io.github.yuxiximyxx.navwake.core.argsArray
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.hook.HookEntry

/**
 * AssistProbe —— 探针①：助理出口。
 *
 * 依据（AOSP）：三键 Home 长按 = `NavigationBarFragment.onHomeLongClick`
 *   → `mAssistManager.startAssist(new Bundle())`
 * 荣耀是 fork 改造，大概率保留同源的 `AssistManager`；类名可能带 Hn 前缀或换包，
 * 所以这里挂一组候选类名，命中哪个都会打日志（找不到不算错，只是没命中）。
 *
 * 选它的理由：`startAssist` 是个**大方法**，不会被 AOT 内联掉（小 getter 会）。
 *
 * ⚠️ 阶段 0 只观察：不吞参数、不改返回值、不跳过原逻辑。
 */
object AssistProbe : Probe() {

    override val id = "assist"

    override val forPackages: Set<String> = HookEntry.SYSTEMUI_PACKAGES

    /** AOSP 原版 + 荣耀可能改造出来的同族类名，逐个尝试，命中即记日志。 */
    private val candidateClasses = listOf(
        "com.android.systemui.assist.AssistManager",
        "com.android.systemui.assist.AssistManagerImpl",
        "com.android.systemui.assist.ui.HnAssistManager",
        "com.hihonor.systemui.assist.AssistManager",
        "com.hihonor.systemui.assist.HnAssistManager",
        "com.android.systemui.assist.HnAssistManager"
    )

    /** 这些方法名只要出现在候选类上，就说明可能是「手势 → 助理」的出口。 */
    private val interestingMethods = listOf(
        "startAssist",
        "onAssistGesture",
        "launchAssist",
        "startVoiceAssist",
        "startAssistant",
        "onLongPressNavigationBar",
        "onHomeLongClick"
    )

    override fun install(context: HookContext) {
        if (!NavConfig.probeAssist()) {
            ModuleLog.i(HookEntry.TAG, "探针 [$id] 被设置关闭，跳过")
            return
        }

        var hit = 0
        for (className in candidateClasses) {
            val clazz = context.findClass(className)
            if (clazz == null) {
                ModuleLog.d(HookEntry.TAG, "探针 [$id] 候选类不存在: $className")
                continue
            }
            ModuleLog.i(
                HookEntry.TAG,
                "探针 [$id] 找到候选类 $className（本类方法数 ${runCatching { clazz.declaredMethods.size }.getOrDefault(-1)}）"
            )

            // ① 先把可疑方法名列出来 —— 就算 hook 不上，日志里也有可复制的类名方法名
            ClassDumpProbe.dumpMethods(context, className, interestingMethods, "Assist")

            for (methodName in interestingMethods) {
                val count = context.hookAllMethodsNamed(
                    clazz = clazz,
                    name = methodName,
                    idPrefix = "$id#${clazz.simpleName}.$methodName"
                ) { chain -> observe(className, methodName, chain) }
                hit += count
            }
        }

        if (hit == 0) {
            ModuleLog.w(
                HookEntry.TAG,
                "探针 [$id] 未命中任何点位 —— 不代表没有，可能类名不同；" +
                    "请靠 startActivity 探针的调用栈反查真实类名"
            )
        } else {
            ModuleLog.i(HookEntry.TAG, "探针 [$id] 共挂载 $hit 个方法")
        }
        installed(context)
    }

    private fun observe(className: String, methodName: String, chain: XposedInterface.Chain): Any? {
        // 关键顺序：先打日志，再 proceed(); 任何打日志的异常都不许影响原调用
        runCatching {
            if (ModuleLog.isVerbose) {
                val args = chain.argsArray().map { describeArg(it) }
                ModuleLog.i(
                    HookEntry.TAG,
                    "★★ ASSIST 命中 $className#$methodName(" + args.joinToString(", ") + ")\n" +
                        Watch.stack()
                )
            }
        }
        return chain.proceed()
    }

    private fun describeArg(arg: Any?): String = when (arg) {
        null -> "null"
        is android.content.Intent -> Watch.describeIntent(arg)
        is android.os.Bundle -> "Bundle{keySet=${runCatching { arg.keySet() }.getOrDefault(emptySet<String>())}}"
        else -> "${arg.javaClass.simpleName}(${runCatching { arg.toString() }.getOrElse { "?" }})"
    }
}
