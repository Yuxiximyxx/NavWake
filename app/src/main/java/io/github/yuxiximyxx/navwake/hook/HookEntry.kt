package io.github.yuxiximyxx.navwake.hook

import android.app.Application
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.yuxiximyxx.navwake.config.NavConfig
import io.github.yuxiximyxx.navwake.config.Packages
import io.github.yuxiximyxx.navwake.core.HookContext
import io.github.yuxiximyxx.navwake.core.ModuleLog
import io.github.yuxiximyxx.navwake.probe.AssistProbe
import io.github.yuxiximyxx.navwake.probe.BrainKeyProbe
import io.github.yuxiximyxx.navwake.probe.ClassDumpProbe
import io.github.yuxiximyxx.navwake.probe.IntentDispatchProbe
import io.github.yuxiximyxx.navwake.probe.MagicVoiceActivityProbe
import io.github.yuxiximyxx.navwake.probe.StartActivityProbe
import java.util.concurrent.ConcurrentHashMap

/**
 * HookEntry —— LSPosed 在目标进程里加载的入口类（见 META-INF/xposed/java_init.list）。
 *
 * 加载顺序（真机踩出来的，别乱动）：
 * 1. 先绑 ModuleLog.module，否则入口日志全部静默丢失；
 * 2. 打无条件 ENTRY 日志 —— 判断「模块到底有没有被注入」全靠它；
 * 3. 绑远程配置；
 * 4. SystemUI 里装重定向 hook（探针模式打开时再叠加阶段 0 探针）；
 *    magicvoice / brain 只在探针模式下装东西，平时完全不碰。
 *
 * 每一步都独立 try/catch（熔断），任何异常都不能让 SystemUI 起不来。
 */
class HookEntry : XposedModule() {

    companion object {
        const val TAG = "HookEntry"

        // 包名常量集中在 config/Packages，别在这里重复定义：
        // 设置页也会用到，而它不能引用本类（见 Packages 的注释）。
        const val MODULE_PACKAGE = Packages.MODULE
        val SYSTEMUI_PACKAGES: Set<String> = Packages.SYSTEMUI
        const val MAGICVOICE = Packages.MAGICVOICE
        const val BRAIN = Packages.BRAIN
        val ALL_PACKAGES: Set<String> = Packages.ALL

        /**
         * 当前进程的 XposedModule 实例，供 [RedirectHook] 每次拦截时**现读**远程配置。
         *
         * 为什么不能只在安装时 bind 一次：LSPosed 的远程 prefs 在注入进程里带缓存，
         * 设置页改了值以后，进程内那份可能还是旧的（真机实测：设了默认目标仍弹框）。
         * 每次拦截重新取一遍，能拿到框架刷新后的值。
         */
        @Volatile
        var moduleInstance: XposedModule? = null

        /** 同一进程可能因多包共用而重复回调，按包名去重。 */
        private val installedPackages = ConcurrentHashMap.newKeySet<String>()

        /** 取当前进程的 Application（拿不到就返回 null，调用方要能容错）。 */
        fun currentApplicationContext(): Application? = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()
    }

    override fun onPackageReady(param: PackageReadyParam) {
        ModuleLog.module = this
        moduleInstance = this

        ModuleLog.i(
            TAG,
            "ENTRY LOADED pkg=${param.packageName} first=${param.isFirstPackage} " +
                "api=${apiVersion} framework=${frameworkName} ${frameworkVersion}"
        )

        if (param.packageName !in ALL_PACKAGES) {
            ModuleLog.d(TAG, "非目标包，忽略: ${param.packageName}")
            return
        }
        if (!installedPackages.add(param.packageName)) {
            ModuleLog.d(TAG, "本进程已安装过 ${param.packageName}，跳过重复回调")
            return
        }

        try {
            install(param)
        } catch (error: Throwable) {
            ModuleLog.e(TAG, "onPackageReady 顶层异常（已吞掉，进程照常启动）", error)
        }
    }

    private fun install(param: PackageReadyParam) {
        runCatching {
            NavConfig.bind(getRemotePreferences(NavConfig.PREFS_NAME))
            ModuleLog.setLevel(NavConfig.logLevel())
        }.onFailure {
            ModuleLog.e(TAG, "读取远程配置失败，按默认值继续", it)
        }

        val probeMode = NavConfig.probeMode()
        ModuleLog.i(
            TAG,
            "安装 pkg=${param.packageName} 探针模式=$probeMode " +
                "重定向=${NavConfig.enabled()} 默认目标=${App_DefaultTarget()}"
        )

        val context = HookContext(
            module = this,
            classLoader = param.classLoader,
            packageName = param.packageName
        )

        when (param.packageName) {
            in SYSTEMUI_PACKAGES -> {
                // ① 重定向（阶段 1 的主功能）
                if (NavConfig.enabled()) {
                    safely("RedirectHook") { RedirectHook.install(context) }
                } else {
                    ModuleLog.i(TAG, "总开关关闭 → 不安装重定向 hook（原 YOYO 行为）")
                }
                // ② 探针（阶段 0 的诊断工具，默认关）
                if (probeMode) installSystemUiProbes(context)
            }

            MAGICVOICE -> if (probeMode) {
                safely("MagicVoiceActivityProbe") { MagicVoiceActivityProbe.install(context) }
                safely("IntentDispatchProbe") { IntentDispatchProbe.install(context) }
            } else {
                ModuleLog.i(TAG, "magicvoice：非探针模式，不安装任何 hook")
            }

            BRAIN -> if (probeMode) {
                safely("BrainKeyProbe") { BrainKeyProbe.install(context) }
            } else {
                ModuleLog.i(TAG, "brain：非探针模式，不安装任何 hook")
            }
        }

        ModuleLog.i(TAG, "安装完成 pkg=${param.packageName} 共 ${context.installedCount} 个点位")
    }

    private fun App_DefaultTarget(): String {
        val pkg = NavConfig.defaultTarget()
        return pkg.ifEmpty { "（未设，每次弹框）" }
    }

    private fun installSystemUiProbes(context: HookContext) {
        runCatching {
            ClassDumpProbe.dumpMethods(
                context,
                "com.android.systemui.assist.AssistManager",
                listOf("start", "assist", "launch", "gesture"),
                "AssistManager"
            )
        }.onFailure { ModuleLog.w(TAG, "类形状 dump 失败（不影响主功能）", it) }

        safely("AssistProbe") { AssistProbe.install(context) }
        safely("StartActivityProbe") { StartActivityProbe.install(context) }
        safely("IntentDispatchProbe") { IntentDispatchProbe.install(context) }
    }

    private inline fun safely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            ModuleLog.e(TAG, "$name 安装失败，已熔断（该项不生效，其它不受影响）", error)
        }
    }
}
