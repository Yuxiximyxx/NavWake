// PalmPurify —— 掌上英雄联盟（com.tencent.qt.qtl）净化模块
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
