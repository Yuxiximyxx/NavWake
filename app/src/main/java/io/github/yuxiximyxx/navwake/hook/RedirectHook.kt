package io.github.yuxiximyxx.navwake.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.yuxiximyxx.navwake.config.AppTargets
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.core.argOrNull

/**
 * RedirectHook —— 阶段 1 的拦截点。**唯一**改行为的地方。
 *
 * ## 拦截什么
 *
 * 真机实测（`docs/stage0-findings.md`）：长按导航条时，SystemUI 会调
 * `startService(com.hihonor.magicvoice/.startup.StartAssistantService)`
 * 去拉起 YOYO 的悬浮窗。所以这里 hook **SystemUI 进程内的** `ContextImpl.startService*`，
 * 只认这一个组件 / action，别的一概原样放行。
 *
 * > 为什么不用 AssistManager：实测长按根本没走那条路（没起 Activity、没走 AssistManager）。
 * > 为什么不 hook magicvoice 侧：语音唤醒、气息唤醒也都进 magicvoice，从里面分不开。
 * > （机主已确认不用语音唤醒和电源键长按，所以这一层误伤风险也不再是约束。）
 *
 * ## 熔断（需求单硬要求：长按绝不能变死键）
 *
 * 除「命中且成功接管」以外的一切情况都放行原 startService：
 * 总开关关 / 锁屏 / 拿不到 context / 三个目标都没装 / 拉起选择框失败 / 回调内异常。
 */
object RedirectHook {

    private const val TAG = "RedirectHook"

    /** 目标服务（真机实测值）。 */
    const val MAGICVOICE_PKG = "com.hihonor.magicvoice"
    const val SERVICE_CLASS = "com.hihonor.magicvoice.startup.StartAssistantService"
    const val SERVICE_ACTION = "com.hihonor.magicvoice.services.STARTASSISTANT"

    val SERVICE_COMPONENT = ComponentName(MAGICVOICE_PKG, SERVICE_CLASS)

    private const val CHOOSER_CLASS = "io.github.yuxiximyxx.navwake.ChooserActivity"

    fun install(context: HookContext) {
        val impl = context.findClass("android.app.ContextImpl") ?: run {
            ModuleLog.w(TAG, "找不到 android.app.ContextImpl，放弃安装（长按照常唤醒 YOYO）")
            return
        }

        var hit = 0
        for (name in listOf("startService", "startServiceCommon", "startForegroundService")) {
            hit += context.hookAllMethodsNamed(
                clazz = impl,
                name = name,
                idPrefix = "redirect#$name",
                filter = { method ->
                    method.parameterTypes.isNotEmpty() &&
                        Intent::class.java.isAssignableFrom(method.parameterTypes[0])
                }
            ) { chain -> intercept(chain) }
        }

        if (hit == 0) {
            ModuleLog.w(TAG, "一个点位都没挂上 —— 保持原行为")
        } else {
            ModuleLog.i(TAG, "重定向 hook 就绪，共 $hit 个点位")
        }
    }

    /**
     * 主入口。返回值即 startService 的返回值。
     *
     * - 非目标调用 → 原样返回 proceed 的结果；
     * - 目标调用且成功接管 → 返回一个「看起来成功」的 ComponentName，不执行原逻辑；
     * - 目标调用但任何环节失败 → 原样 proceed（YOYO 照常弹）。
     */
    private fun intercept(chain: XposedInterface.Chain): Any? {
        val isTarget = runCatching { isYoyoStart(chain) }.getOrDefault(false)
        if (!isTarget) return chain.proceed()

        val context = HookEntry.currentApplicationContext()
        if (context == null) {
            ModuleLog.w(TAG, "拿不到 Application context，放行原行为")
            return chain.proceed()
        }

        val handled = try {
            tryIntercept(context)
        } catch (error: Throwable) {
            ModuleLog.e(TAG, "拦截处理异常，熔断放行原 YOYO", error)
            false
        }

        // 接管成功：不 proceed，并返回一个「成功」的样子，避免调用方把 null 当失败
        if (handled) return SERVICE_COMPONENT
        return chain.proceed()
    }

    /** @return true 表示已接管（不要执行原 startService）。 */
    private fun tryIntercept(context: Context): Boolean {
        // 每次拦截现读一次配置：LSPosed 远程 prefs 在进程内有缓存，
        // 不刷新就会一直用安装 hook 时的旧值。
        NavConfig.refresh { HookEntry.moduleInstance?.getRemotePreferences(NavConfig.PREFS_NAME) }

        if (!NavConfig.enabled()) {
            ModuleLog.d(TAG, "总开关关闭，放行")
            return false
        }

        if (isKeyguardLocked(context)) {
            ModuleLog.d(TAG, "锁屏状态，放行（v1 不拦锁屏）")
            return false
        }

        val targets = AppTargets.installed(context)
        if (targets.isEmpty()) {
            ModuleLog.w(TAG, "三个目标都没装，放行")
            return false
        }

        // ① 已设默认且仍可用 → 直达
        val defaultTarget = targets.firstOrNull { it.pkg == NavConfig.defaultTarget() }
        if (defaultTarget != null && targets.any { it.pkg == defaultTarget.pkg }) {
            if (AppTargets.launch(context, defaultTarget)) {
                ModuleLog.i(TAG, "命中：直达默认目标 ${defaultTarget.displayName}")
                return true
            }
            ModuleLog.w(TAG, "拉起默认目标失败，改弹选择框")
        }

        // ② 未设默认 / 默认失效 → 弹选择框
        if (AppTargets.launchChooser(context, CHOOSER_CLASS, HookEntry.MODULE_PACKAGE)) {
            ModuleLog.i(TAG, "命中：弹出选择框（可用目标 ${targets.size} 个）")
            return true
        }

        ModuleLog.w(TAG, "选择框拉起失败，放行原 YOYO")
        return false
    }

    /** 这次 startService 是不是「导航条 → YOYO」。 */
    private fun isYoyoStart(chain: XposedInterface.Chain): Boolean {
        val intent = chain.argOrNull(0) as? Intent ?: return false
        val component = intent.component
        if (component != null &&
            component.packageName == MAGICVOICE_PKG &&
            component.className == SERVICE_CLASS
        ) {
            return true
        }
        // 隐式 Intent（只给 action）时靠 action 认
        return intent.action == SERVICE_ACTION
    }

    private fun isKeyguardLocked(context: Context): Boolean = runCatching {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        km?.isKeyguardLocked ?: false
    }.getOrDefault(false)
}
