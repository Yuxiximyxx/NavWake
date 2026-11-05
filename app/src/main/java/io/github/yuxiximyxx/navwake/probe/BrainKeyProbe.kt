package io.github.yuxiximyxx.navwake.probe

import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.libxposed.api.XposedInterface
import io.github.yuxiximyxx.navwake.core.argsArray
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.hook.HookEntry

/**
 * BrainKeyProbe —— 探针④：电源键 / AI 键动作的对照组。
 *
 * 依据（公开资料）：MagicOS 11 的按键动作由 `com.hihonor.brain` 的
 * `KeyActionServiceImpl.handleKeyAction` 执行，配置存在 `settings system` 的
 * `ai_key_*_service_info` JSON 里，Intent URI 内用 `S.pressMethod=long_press/double_press`
 * 区分触发源。
 *
 * 作用：需求单 Q2 要「电源键长按的调用链，和导航条这条差在哪」。
 * 把这一路也打成日志，两条链一比就能找出**可区分的标志**。
 *
 * ⚠️ 只观察。这一路即使挂了也绝不能影响电源键正常功能。
 */
object BrainKeyProbe : Probe() {

    override val id = "brain-key"

    override val forPackages: Set<String> = setOf(HookEntry.BRAIN)

    /** 类名未知，按可能的位置逐个试；命中哪个记哪个。 */
    private val candidateClasses = listOf(
        "com.hihonor.brain.KeyActionServiceImpl",
        "com.hihonor.brain.service.KeyActionServiceImpl",
        "com.hihonor.brain.key.KeyActionServiceImpl",
        "com.hihonor.brain.action.KeyActionServiceImpl",
        "com.hihonor.brain.keyaction.KeyActionServiceImpl"
    )

    private val interestingMethods = listOf("handleKeyAction", "onKeyAction", "performKeyAction")

    override fun install(context: HookContext) {
        if (!NavConfig.probeBrain()) {
            ModuleLog.i(HookEntry.TAG, "探针 [$id] 被设置关闭，跳过")
            return
        }

        var hit = 0
        for (className in candidateClasses) {
            val clazz = context.findClass(className) ?: continue
            ModuleLog.i(HookEntry.TAG, "探针 [$id] 找到 $className")
            ClassDumpProbe.dumpMethods(context, className, interestingMethods, "Brain")

            for (methodName in interestingMethods) {
                hit += context.hookAllMethodsNamed(
                    clazz = clazz,
                    name = methodName,
                    idPrefix = "$id#$methodName"
                ) { chain -> observe(className, methodName, chain) }
            }
        }

        if (hit == 0) {
            ModuleLog.w(
                HookEntry.TAG,
                "探针 [$id] 未命中 —— 类名与公开资料不一致；" +
                    "可用 dump 日志里列出的实际类名再补"
            )
        } else {
            ModuleLog.i(HookEntry.TAG, "探针 [$id] 共挂载 $hit 个方法")
        }

        // 顺手把按键配置读出来（只读，不改）—— 里面通常写着该按键拉起什么
        SettingsDumpProbe.dumpKeyActionSettings(context)
        installed(context)
    }

    private fun observe(className: String, methodName: String, chain: XposedInterface.Chain): Any? {
        runCatching {
            val args = chain.argsArray().joinToString(", ") { arg ->
                when (arg) {
                    null -> "null"
                    is Intent -> Watch.describeIntent(arg)
                    else -> runCatching { arg.toString() }.getOrElse { "?" }
                }
            }
            ModuleLog.i(
                HookEntry.TAG,
                "★★ BRAIN 命中 $className#$methodName($args)\n" + Watch.stack(maxFrames = 12)
            )
        }
        return chain.proceed()
    }
}

/**
 * SettingsDumpProbe —— 纯读取：把导航条 / 助理 / AI 键相关的系统设置键打出来。
 *
 * 阶段 0 想回答的「开关在哪、当前值是什么、有没有能区分来源的参数」，
 * 一半答案就在这些设置项里。只读不写。
 */
object SettingsDumpProbe {

    private val keyKeywords = listOf("nav", "assist", "ai_key", "yoyo", "voice", "wake")

    fun dumpKeyActionSettings(context: HookContext) {
        val appContext = HookEntry.currentApplicationContext() ?: run {
            ModuleLog.w(HookEntry.TAG, "[dump:settings] 拿不到 Application context，跳过")
            return
        }
        runCatching {
            // Settings.System 全量键值（隐藏 API，反射取；失败就算了）
            @Suppress("UNCHECKED_CAST")
            val map = Settings.System::class.java
                .getMethod("getAll", Class.forName("android.content.ContentResolver"))
                .invoke(null, appContext.contentResolver) as? Map<String, String>
                ?: emptyMap()

            val hits = map.filterKeys { key ->
                keyKeywords.any { key.lowercase().contains(it) }
            }
            ModuleLog.i(
                HookEntry.TAG,
                "[dump:settings] settings/system 共 ${map.size} 项，命中关键词 ${hits.size} 项"
            )
            hits.forEach { (key, value) ->
                ModuleLog.i(HookEntry.TAG, "[dump:settings]   $key = $value")
            }
        }.onFailure {
            ModuleLog.w(HookEntry.TAG, "[dump:settings] 读取 settings/system 失败（不影响功能）", it)
        }

        // 标准安卓助理通道的当前指向：用来反证「这条长按确实没走标准通道」
        runCatching {
            val resolver = appContext.contentResolver
            val voice = Settings.Secure.getString(resolver, "voice_interaction_service")
            val assistant = Settings.Secure.getString(resolver, "assistant")
            ModuleLog.i(HookEntry.TAG, "[dump:settings] voice_interaction_service = $voice")
            ModuleLog.i(HookEntry.TAG, "[dump:settings] assistant = $assistant")
        }.onFailure {
            ModuleLog.w(HookEntry.TAG, "[dump:settings] 读取 Secure 设置失败（不影响功能）", it)
        }
    }

    /** 当前是否锁屏 —— 阶段 1 要用它决定拦不拦（v1 锁屏不拦）。 */
    fun isKeyguardLocked(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
            .isKeyguardLocked
    }.getOrDefault(false)
}
