package io.github.yuxiximyxx.navwake.core

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * HookContext —— 每个进程一份的 hook 安装上下文。
 *
 * 设计原则（计划书阶段 4「熔断」）：
 * - 任何一次安装失败都只记日志、返回 false，绝不让调用方向外抛异常；
 * - 任何一次 hook 执行期异常都打印堆栈后 `chain.proceed()`，放行原逻辑；
 * - 找不到类 / 方法 = 正常情况（版本更新/加固改类），用 warn 而不是 error。
 */
class HookContext(
    val module: XposedModule,
    val classLoader: ClassLoader,
    val packageName: String
) {
    private companion object {
        const val TAG = "HookContext"
    }

    /** 本进程成功安装的 hook 数量，供入口日志汇总。 */
    var installedCount: Int = 0
        private set

    // ── 类查找 ─────────────────────────────────────────────────────

    fun findClass(name: String): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()

    fun classExists(name: String): Boolean = findClass(name) != null

    // ── 方法查找 ───────────────────────────────────────────────────

    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = runCatching {
        clazz.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
    }.getOrNull()

    fun methodsNamed(clazz: Class<*>, name: String): List<Method> =
        runCatching { clazz.declaredMethods.filter { it.name == name } }.getOrDefault(emptyList())

    fun constructorsOf(clazz: Class<*>): List<Constructor<*>> =
        runCatching { clazz.declaredConstructors.toList() }.getOrDefault(emptyList())

    /**
     * 按「方法名 + 自定义条件」找方法。目标类常有多个重载，
     * 用 returnType/参数类型名做谓词比硬写参数 Class 更抗改。
     */
    fun methodMatching(
        className: String,
        name: String,
        predicate: (Method) -> Boolean = { true }
    ): Method? {
        val clazz = findClass(className) ?: return null
        return runCatching {
            clazz.declaredMethods.firstOrNull { it.name == name && predicate(it) }
        }.getOrNull()?.also { it.isAccessible = true }
    }

    /** 读对象上的字段（含私有），失败返回 null。 */
    fun fieldValue(target: Any, name: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null && clazz != Any::class.java) {
            runCatching { clazz!!.getDeclaredField(name) }.getOrNull()?.let { field ->
                return runCatching { field.isAccessible = true; field.get(target) }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    // ── 安装 ──────────────────────────────────────────────────────

    /**
     * 安装一个 hook。
     *
     * @param id 唯一标识，写进 LSPosed 日志便于定位是哪一项生效/失效
     * @param priority 默认 PRIORITY_DEFAULT；需要压过其它模块时再调
     */
    fun hook(
        executable: Executable,
        id: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        handler: (XposedInterface.Chain) -> Any?
    ): Boolean = try {
        module.hook(executable)
            .setId(id)
            .setPriority(priority)
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    return try {
                        handler(chain)
                    } catch (error: Throwable) {
                        // 熔断：hook 自身出错一律放行原逻辑
                        ModuleLog.e(TAG, "hook 执行异常 id=$id，放行原逻辑", error)
                        chain.proceed()
                    }
                }
            })
        installedCount++
        ModuleLog.d(TAG, "hook OK  id=$id  ← ${executable.declaringClass.name}#${executable.name}")
        true
    } catch (error: Throwable) {
        ModuleLog.w(
            TAG,
            "hook 安装失败 id=$id  ${executable.declaringClass.name}#${executable.name}: " +
                "${error.javaClass.simpleName}: ${error.message}"
        )
        false
    }

    /**
     * 按方法名安装该类上**所有**同名重载（系统类重载多、签名不稳，这是最常用的入口）。
     *
     * @param filter 只装符合条件的方法，例如 `{ it.returnType == Boolean::class.javaPrimitiveType }`
     * @return 成功安装的数量
     */
    fun hookAllMethodsNamed(
        clazz: Class<*>,
        name: String,
        idPrefix: String,
        filter: (Method) -> Boolean = { true },
        handler: (XposedInterface.Chain) -> Any?
    ): Int {
        val targets = methodsNamed(clazz, name).filter(filter)
        if (targets.isEmpty()) {
            ModuleLog.w(TAG, "方法不存在 id=$idPrefix :: ${clazz.name}.$name()")
            return 0
        }
        var count = 0
        targets.forEachIndexed { index, method ->
            method.isAccessible = true
            if (hook(method, "$idPrefix#$index", handler = handler)) count++
        }
        return count
    }

    /** 安装所有构造器（用于「拦截 SDK 初始化」这类场景）。 */
    fun hookAllConstructors(
        clazz: Class<*>,
        idPrefix: String,
        handler: (XposedInterface.Chain) -> Any?
    ): Int {
        var count = 0
        constructorsOf(clazz).forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            if (hook(constructor, "$idPrefix#ctor$index", handler = handler)) count++
        }
        return count
    }

    /**
     * 便捷写法：hook 一个方法，先执行原逻辑，再把结果交给 [transform] 处理。
     * 典型用法：返回值变换。
     */
    fun hookResult(
        executable: Executable,
        id: String,
        transform: (XposedInterface.Chain, Any?) -> Any?
    ): Boolean = hook(executable, id) { chain ->
        val original = chain.proceed()
        // 关键：transform 出错必须退回原结果，绝不能把异常抛到 hook() 的 catch ——
        // 那里会再 proceed 一次，等于原方法执行两遍（副作用方法尤其危险）。
        try {
            transform(chain, original)
        } catch (error: Throwable) {
            ModuleLog.e(TAG, "hookResult 变换异常 id=$id，退回原结果", error)
            original
        }
    }

    /** 便捷写法：hook 一个 void 方法，执行完原逻辑后做副作用（如 onResume 后改窗口）。 */
    fun hookAfter(
        executable: Executable,
        id: String,
        sideEffect: (XposedInterface.Chain) -> Unit
    ): Boolean = hook(executable, id) { chain ->
        val original = chain.proceed()
        try {
            sideEffect(chain)
        } catch (error: Throwable) {
            ModuleLog.e(TAG, "hookAfter 副作用异常 id=$id（已忽略）", error)
        }
        original
    }
}

// ── Chain 便捷取值 ──────────────────────────────────────────────

fun XposedInterface.Chain.argOrNull(index: Int): Any? =
    runCatching { getArgs().getOrNull(index) }.getOrNull()

fun XposedInterface.Chain.thisOrNull(): Any? = runCatching { thisObject }.getOrNull()

/** 只读快照，避免 handler 里直接改 getArgs() 返回的只读列表。 */
fun XposedInterface.Chain.argsArray(): Array<Any?> =
    runCatching { getArgs().toTypedArray() }.getOrDefault(emptyArray())
