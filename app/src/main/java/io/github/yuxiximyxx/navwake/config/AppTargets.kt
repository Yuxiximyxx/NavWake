package io.github.yuxiximyxx.navwake.config

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * 可被唤起的应用目标。
 *
 * **源码里不含任何具体应用包名** —— 目标由用户在设置页里自己选，
 * 存在模块本地配置里。这样代码可以公开，不会暴露作者装了哪些 App。
 *
 * 老的「默认目标」配置会被自动迁移成「已选目标」里的第一项，升级不用重设。
 */
object AppTargets {

    data class Target(val pkg: String, val displayName: String)

    /** 用户在设置页里勾选过的包名（顺序即展示顺序）。 */
    fun selectedPackages(): List<String> {
        val saved = NavConfig.selectedPackages()
        if (saved.isNotEmpty()) return saved
        // 迁移：老版本只有一个「默认目标」，把它当作唯一已选目标
        val legacy = NavConfig.defaultTarget()
        return if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
    }

    fun saveSelectedPackages(context: Context, packages: List<String>) {
        NavConfig.setSelectedPackages(packages.toList())
        // 默认目标若已不在选择里，顺带清掉，避免指向一个不可见的目标
        if (NavConfig.defaultTarget().isNotEmpty() && NavConfig.defaultTarget() !in packages) {
            NavConfig.clearDefaultTarget()
        }
    }

    /** 已配置的目标（不校验是否还装着）。 */
    fun all(context: Context): List<Target> =
        selectedPackages().map { Target(it, labelOf(context, it)) }

    /** 已配置且当前能启动的目标 —— 选择框只显示这些。 */
    fun installed(context: Context): List<Target> =
        all(context).filter { resolveLaunchIntent(context, it) != null }

    fun resolveLaunchIntent(context: Context, target: Target): Intent? =
        runCatching { context.packageManager.getLaunchIntentForPackage(target.pkg) }.getOrNull()

    fun launch(context: Context, target: Target): Boolean = runCatching {
        val intent = resolveLaunchIntent(context, target) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun labelOf(context: Context, pkg: String?): String {
        if (pkg.isNullOrEmpty()) return "（未设置）"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** 打开本模块的选择框。成功（发起）返回 true。 */
    fun launchChooser(context: Context, chooserClass: String, modulePackage: String): Boolean =
        runCatching {
            val intent = Intent().apply {
                component = ComponentName(modulePackage, chooserClass)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

    // ── 设置页用：列出本机所有可启动的应用，供勾选 ────────────────────

    data class AppEntry(val pkg: String, val label: String)

    /** 本机所有有启动入口的应用，按名称排序，排除自己。 */
    fun launchableApps(context: Context, excludePackage: String): List<AppEntry> = runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        resolved.asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == excludePackage) return@mapNotNull null
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                AppEntry(pkg, label)
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}
