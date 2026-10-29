import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 读取 release 签名材料。返回 null 表示未配置。
 *
 * 来源优先级：
 *   ① 环境变量 AIHUB_STORE_FILE / AIHUB_STORE_PASSWORD / AIHUB_KEY_ALIAS / AIHUB_KEY_PASSWORD
 *      （CI 从 GitHub Secrets 注入，密钥材料不落仓库）
 *   ② ~/.android/aihub-signing.properties（本地开发，同样不进版本控制）
 *   ③ 都没有 → null，调用方退回 debug 签名
 */
fun releaseSigning(): Map<String, String>? {
    fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    val envFile = env("AIHUB_STORE_FILE")
    if (envFile != null) {
        return mapOf(
            "storeFile" to envFile,
            "storePassword" to (env("AIHUB_STORE_PASSWORD") ?: return null),
            "keyAlias" to (env("AIHUB_KEY_ALIAS") ?: "aihub"),
            "keyPassword" to (env("AIHUB_KEY_PASSWORD") ?: env("AIHUB_STORE_PASSWORD")!!),
        )
    }

    val propFile = File(System.getProperty("user.home"), ".android/aihub-signing.properties")
    if (propFile.isFile) {
        val props = Properties()
        propFile.inputStream().use { props.load(it) }
        val f = props.getProperty("storeFile") ?: return null
        return mapOf(
            "storeFile" to f,
            "storePassword" to (props.getProperty("storePassword") ?: return null),
            "keyAlias" to (props.getProperty("keyAlias") ?: "aihub"),
            "keyPassword" to (props.getProperty("keyPassword") ?: props.getProperty("storePassword")!!),
        )
    }
    return null
}

fun hasReleaseSigning(): Boolean = releaseSigning() != null

android {
    signingConfigs {
        create("release") {
            releaseSigning()?.let { c ->
                storeFile = File(c.getValue("storeFile"))
                storePassword = c.getValue("storePassword")
                keyAlias = c.getValue("keyAlias")
                keyPassword = c.getValue("keyPassword")
            }
            // 关掉 v1 签名：minSdk 24 起系统一律走 v2/v3，
            // 保留 v1 只会增加 jar 校验开销，且与部分增量安装器兼容性差。
            enableV1Signing = false
            enableV2Signing = true
        }
    }

    namespace = "com.dsh.aihub"

    /**
     * compileSdk 可配置化（逃生口）。
     *
     * 默认值 "android-37.0" 与 CI 保持一致，正常情况下无需覆盖。
     *
     * 背景（三点都验证过，改这里前请先读）：
     *   1) 必须用字符串版 compileSdkVersion 而不是 `compileSdk = 37` ——
     *      后者会去找名为 `android-37` 的目录并报 platform not found；
     *   2) "android-37.0" 位于 Google 的预览（preview）channel，不在稳定 channel，
     *      因此 CI 需用 `sdkmanager --channel=3 "platforms;android-37.0"` 安装；
     *   3) 不能降到 android-36：Miuix 0.9.1 的 AAR 元数据硬性要求 compileSdk >= 37，
     *      降版会被 checkDebugAarMetadata 直接拒绝。
     *
     * 覆盖方式（仅当需要临时换平台时）：
     *   ./gradlew -PcompileSdk=android-36 :app:assembleDebug
     */
    val compileSdkName: String = (project.findProperty("compileSdk") as String?) ?: "android-37.0"
    compileSdkVersion(compileSdkName)
    // 必须指定 build-tools 34.0.4：
    //   仓库里 35/36 的 aidl 是 x86-64 二进制，在 aarch64 上直接 bad machine；
    //   34.0.4 是仅有的 aarch64 版，只有它能跑 AIDL 编译（Shizuku UserService 需要）。
    //   aapt2 问题性质不同 —— 那个是 proot linker，已由 aapt2FromMavenOverride 解决。
    buildToolsVersion = "34.0.4"

    defaultConfig {
        applicationId = "com.dsh.aihub"
        minSdk = 24
        targetSdk = 36
        // 版本号随功能变更递增：便于在设备上区分"是否装上的是新版"
        // （同 versionCode 覆盖安装后，界面上无从判断装的是哪一版）。
        //
        // 云端打包（.github/workflows/build-apk.yml）通过 -P 传入版本号：
        //   -PappVersionCode=200 -PappVersionName=2.0.0
        //
        // 为什么用 -P 而不是环境变量：Gradle daemon 会把【启动时】的环境变量
        // 固化在进程里，之后用 System.getenv 读到的是 daemon 的环境而非本次
        // 调用的环境 —— 实测传 APP_VERSION_CODE 后 versionCode 仍是默认 33。
        // project property 每次调用都会传给 daemon，不会有这个问题。
        //
        // 环境变量仍作为兜底（CI 里若直接 export 也能生效，前提是无 daemon）。
        // 两者都没有时回退默认值，本地 `./gradlew assembleDebug` 行为不变。
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull()
            ?: System.getenv("APP_VERSION_CODE")?.toIntOrNull()
            ?: 33
        versionName = (findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() }
            ?: "1.9.1-beta.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 必须用**固定**的 release keystore，不能用 debug 签名。
            //
            // 为什么：debug keystore 是每台机器/每次重建自动生成的，
            // 同一份源码在不同环境构建出的证书不同 —— 系统判定为不同应用，
            // 更新时直接报「签名冲突」拒绝覆盖安装。
            // 本项目确实不上架商店，但**应用内更新必须能覆盖安装**，
            // 所以签名必须跨构建、跨机器稳定。
            //
            // 密钥材料不进仓库：优先读环境变量（CI 用 secrets），
            // 其次读本地 ~/.android/aihub-signing.properties。
            // 两者都没有时退回 debug 签名，仅用于本地临时验证。
            signingConfig = signingConfigs.getByName(
                if (hasReleaseSigning()) "release" else "debug"
            )
            if (!hasReleaseSigning()) {
                logger.warn(
                    "WARNING: release keystore 未配置，本次使用 debug 签名。" +
                        "该 APK 无法覆盖已安装的正式版本，仅限本地临时验证。"
                )
            }
        }
    }

    buildFeatures {
        compose = true
        // Shizuku UserService 是跨进程调用，接口必须走 AIDL。
        // 官方已把 Shizuku.newProcess 收回 private，UserService + AIDL
        // 是当前唯一稳定的执行 shell 的路径。
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    configurations.all {
        // Compose 版本强制锁 1.11.2。
        //
        // 为什么必须锁：Miuix 0.9.4 传递依赖 compose 1.12.0，而 1.12.0 的 AAR 元数据
        // 声明 minAndroidGradlePluginVersion = 9.1.0。沙箱内可用的 AGP 只能到 8.13.2
        // （build-tools 36+ 只有 x86_64 版，AGP 9 所需的 aapt2 无法在 ARM64 上执行），
        // 因此必须把 compose 压回 1.11.2 —— 它只要求 AGP 8.6.0。
        resolutionStrategy {
            force("androidx.compose.ui:ui:1.11.2")
            force("androidx.compose.foundation:foundation:1.11.2")
            force("androidx.compose.animation:animation:1.11.2")
            force("androidx.compose.runtime:runtime:1.11.2")
            force("androidx.compose.ui:ui-graphics:1.11.2")
            force("androidx.compose.ui:ui-unit:1.11.2")
            force("androidx.compose.ui:ui-text:1.11.2")
            force("androidx.compose.ui:ui-util:1.11.2")
            force("androidx.compose.foundation:foundation-layout:1.11.2")
            force("androidx.compose.runtime:runtime-saveable:1.11.2")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Shizuku：借助 ADB/root 授权执行系统级操作（这里用于 appops 放行与 battery 白名单），
    // 免去用户手动逐项点系统设置。api 提供运行时接口，provider 提供权限声明。
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ---- Compose 基建（版本与上方 force 一致，显式声明以免靠传递依赖碰运气）----
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui:1.11.2")
    implementation("androidx.compose.foundation:foundation:1.11.2")
    implementation("androidx.compose.material3:material3")
    // Miuix 弹层（对话框 / 下拉菜单）内部注册 NavigationBackHandler，要求 Composition 中存在
    // LocalNavigationEventDispatcherOwner；而 Compose Dialog 是独立 window、View 树取不到宿主的
    // owner（默认值按 R.id.view_tree_navigation_event_dispatcher_owner 从 View 树查找），
    // 不提供就会崩：No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner。
    // 修复实现在 ui/NavCompat.kt。
    //
    // 为什么必须显式声明这两个坐标：
    //   Miuix 0.9.1 的 pom 声明的是 org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0，
    //   但该 group 下【只有 compose 模块、没有 core 模块】（Maven Central 上 core 为 404），
    //   属上游声明残缺；因此 NavigationEventDispatcher 等核心类拿不到，编译不过。
    //   实际运行期打进 APK 的类包名就是 androidx.navigationevent.*（已核对 1.1.x 字节码），
    //   故改用 androidx 官方坐标：与既有类同源，不会产生重复类或运行时冲突。
    // 两个坐标都必须显式声明：
    //   core 提供 NavigationEventDispatcher / Owner 接口；compose 提供 LocalNavigationEventDispatcherOwner
    //   与 rememberNavigationEventDispatcherOwner。
    // 只加 core 会把 compose 部分挤出编译类路径（core 不含 compose 组件），反之则缺核心类。
    implementation("androidx.navigationevent:navigationevent:1.1.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // FileProvider（应用内更新把 APK 交给安装器需要 content:// URI）。
    // 虽然 appcompat 会传递引入 androidx.core，但显式声明避免上游换实现后编译断裂。
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ---- Miuix（小米 HyperOS 风格 UI）----
    // 版本必须锁 0.9.1：它的 kotlin-stdlib 依赖 2.3.21，与本地 Kotlin 编译器一致；
    // 0.9.2/0.9.3 要求 Kotlin 2.4.0，0.9.4 的传递依赖会把 compose 拉到 1.12.0（见上）。
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.1")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.1")

    // ---- 单元测试（JVM，不跑设备）----
    // 只用于纯逻辑：失败分类、协议转换这类「写错了不会崩、只会静默给出错误结论」
    // 的函数。当前覆盖 FailureDiagnosis（见 app/src/test）。
    testImplementation("junit:junit:4.13.2")
    // org.json 在 Android 单元测试里是 stub（方法全抛 RuntimeException），
    // 必须给 JVM 侧一份真实实现，否则任何碰 JSONObject 的用例都会挂。
    testImplementation("org.json:json:20240303")
}
