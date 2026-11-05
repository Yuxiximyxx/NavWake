package io.github.yuxiximyxx.navwake.config

/**
 * 与本模块相关的包名常量。
 *
 * ⚠️ 单独放这里、不放进 [io.github.yuxiximyxx.navwake.hook.HookEntry]：
 * HookEntry 继承 `XposedModule`，而 `libxposed:api` 是 compileOnly（不打进 APK）。
 * 设置页进程里一旦引用 HookEntry，就会因为父类缺失抛
 * `NoClassDefFoundError: ...HookEntry` —— 真机踩过，整页白屏。
 *
 * 所以：**只有注入进程里才能碰 HookEntry，设置页只能碰这个对象。**
 */
object Packages {

    const val MODULE = "io.github.yuxiximyxx.navwake"

    /** SystemUI 包名候选（真机实测是前者，后者真机上不存在，留着兜底）。 */
    val SYSTEMUI: Set<String> = setOf(
        "com.android.systemui",
        "com.hihonor.systemui"
    )

    const val MAGICVOICE = "com.hihonor.magicvoice"
    const val BRAIN = "com.hihonor.brain"

    val ALL: Set<String> = SYSTEMUI + setOf(MAGICVOICE, BRAIN)
}
