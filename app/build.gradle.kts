import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 自有 release 签名（计划书阶段 4）。*.jks 在 .gitignore 里，克隆后不存在时回退 debug 签名。
val releaseKeystore = file("signing/navwake.jks")

android {
    namespace = "io.github.yuxiximyxx.navwake"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.yuxiximyxx.navwake"
        // libxposed API 102 要求 minSdk >= 26；本模块是独立 APK，不受被 hook 进程的 minSdk 影响
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.3.0"
    }

    signingConfigs {
        // 调试签名也收进工作区：默认位置是 ~/.android/debug.keystore，
        // 在受限沙箱里该目录不可写，会导致 validateSigningRelease 直接失败。
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = releaseKeystore
            storePassword = "navwake123"
            keyAlias = "navwake"
            keyPassword = "navwake123"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            // 关闭混淆：LSPosed 靠 java_init.list 里的类全名反射加载入口，
            // 混淆一旦改名就是「构建成功但模块静默不生效」，省下的几百 KB 不抵这个风险。
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("release")
            } else {
                println("NavWake: 未找到 $releaseKeystore，assembleRelease 将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
        }
    }

    dependenciesInfo.includeInApk = false

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "kotlin/**"
            excludes += "**.bin"
        }
    }
}

// 打包后校验 LSPosed 现代模块声明（META-INF/xposed 下的三个文件）。
// LSPosed 判断「这是不是模块」只看 APK zip 里有没有 META-INF/xposed/java_init.list。
// 该文件一旦被 AGP 的 packaging 规则吞掉，模块会从 LSPosed 列表里彻底消失，
// 而构建仍然「成功」—— 所以这里强制校验，宁可构建失败也不交付哑模块。
val verifyXposedModule = tasks.register("verifyXposedModule") {
    group = "verification"
    description = "校验 APK 内 META-INF/xposed 模块声明是否完整，防止 LSPosed 识别不到模块"

    val apkDirectory = layout.buildDirectory.dir("outputs/apk")

    doLast {
        val dir = apkDirectory.get().asFile
        val apks = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        } else {
            emptyList()
        }

        if (apks.isEmpty()) {
            logger.warn("verifyXposedModule: $dir 下没有 APK，跳过校验")
            return@doLast
        }

        var failed = false
        for (apk in apks) {
            logger.lifecycle("verifyXposedModule: 检查 ${apk.name}")
            try {
                ZipFile(apk).use { zip ->
                    val javaInit = zip.getEntry("META-INF/xposed/java_init.list")
                    if (javaInit == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 META-INF/xposed/java_init.list —— LSPosed 不会识别为模块")
                    } else {
                        val entries = zip.getInputStream(javaInit).bufferedReader()
                            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (entries.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] java_init.list 为空")
                        } else {
                            entries.forEach { logger.lifecycle("  [OK] 入口类: $it") }
                        }
                    }

                    val prop = zip.getEntry("META-INF/xposed/module.prop")
                    if (prop == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 META-INF/xposed/module.prop")
                    } else {
                        val map = zip.getInputStream(prop).bufferedReader().readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                            .associate { line ->
                                val idx = line.indexOf('=')
                                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                            }
                        logger.lifecycle("  [OK] module.prop: $map")
                        if (!map.containsKey("minApiVersion")) {
                            failed = true
                            logger.error("  [FAIL] module.prop 缺少 minApiVersion")
                        }
                        if (!map.containsKey("targetApiVersion")) {
                            logger.warn("  [WARN] module.prop 缺少 targetApiVersion（建议显式声明）")
                        }
                    }

                    val scope = zip.getEntry("META-INF/xposed/scope.list")
                    if (scope == null) {
                        failed = true
                        logger.error("  [FAIL] 缺少 META-INF/xposed/scope.list —— 作用域为空，模块不生效")
                    } else {
                        val scopes = zip.getInputStream(scope).bufferedReader().readLines()
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        if (scopes.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] scope.list 为空")
                        } else {
                            scopes.forEach { logger.lifecycle("  [OK] 作用域: $it") }
                        }
                        if (!scopes.contains("com.android.systemui")) {
                            failed = true
                            logger.error("  [FAIL] scope.list 不含 com.android.systemui")
                        }
                    }
                }
            } catch (error: Throwable) {
                failed = true
                logger.error("  [FAIL] 读取 ${apk.name} 失败: ${error.message}")
            }
        }

        if (failed) {
            throw GradleException("verifyXposedModule 失败：APK 内 LSPosed 声明不完整")
        }
        logger.lifecycle("verifyXposedModule: 全部通过")
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach {
    finalizedBy(verifyXposedModule)
}

// libxposed 仓库里的 AAR 没带 minCompileSdk 元数据，跳过 AGP 的 AAR 元数据校验，
// 否则 compileSdk=34 会在 checkDebugAarMetadata 阶段被拒。
tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask>().configureEach {
    enabled = false
}

// libxposed service 的 AAR 带的是 kotlin-stdlib 2.2.x，和本项目的 Kotlin 2.0.21 编译器不兼容
// （metadata 2.2 无法被 2.0 读）。统一压回 2.0.21 —— MagicFree4 同样处理，属于已知坑。
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("2.0.21")
            because("必须与 Kotlin 编译器版本一致，否则 metadata 版本不兼容")
        }
    }
}

dependencies {
    // libxposed：api 只编译期可见（运行期由 LSPosed 注入），service 打进 APK 供设置页连服务
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:101.0.0")
}
