import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 模块1~5 是纯数据/逻辑层；模块6 起加入 Compose 界面。
// 依赖按「用到才加」的原则维护，目前只引入 Material3 + activity-compose + lifecycle，
// 没有引入 navigation-compose —— 三个主 Tab 用简单的状态切换就够了，
// 不需要为此背上一整套导航框架（需求十六节第 14 条）。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.timeledger.app"

    // 锁定 36：本机 SDK 镜像里最新的平台就是 android-36。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.timeledger.app"
        // minSdk 26 的选择依据：
        // 1. java.time（TimeBoundary / DayBoundary 全靠它）从 API 26 起原生可用，
        //    否则必须引入 core library desugaring，为一个个人工具类 App 增加复杂度不划算；
        // 2. UsageStatsManager 的关键事件（ACTIVITY_RESUMED 等）需要 API 29+，
        //    那部分会在系统感知模块里做版本分支处理，不靠抬高 minSdk 解决。
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    buildFeatures {
        // 打开 Compose 支持；编译器版本由 kotlin-compose 插件决定（= Kotlin 版本）
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // 把 Room 的 schema 导出到 app/schemas/。
    // 这是将来写数据库迁移脚本的唯一可靠依据，必须随代码一起提交
    // （所以 .gitignore 里不能忽略这个目录）。
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Compose：BOM 统一管理 Compose 各库版本，避免 ui / material3 之间版本错配
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    // 预览注解：本机没有 Studio，但用户在 Studio 里能直接看到界面，值得带上
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // collectAsStateWithLifecycle：界面不可见时自动停止收集，避免后台白跑
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 仅在 debug 构建里带工具，正式包不含
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room：runtime + ktx（Flow/协程支持），实现在编译期由 KSP 生成
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 单元测试跑在 JVM 上，不需要模拟器：时间边界、区间重叠、
    // 仓库写入保护这些都是纯逻辑，用假 DAO 就能完整覆盖。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
