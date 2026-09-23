import com.android.build.api.dsl.ApplicationExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

/**
 * 正式发布签名。
 *
 * 凭据放仓库根的 `keystore.properties`（`.gitignore` 已覆盖它和 `*.jks`），
 * **keystore 本体刻意放在仓库外** —— 见 .gitignore 里「签名件绝不入库」那条注释的立场，
 * 放进工作区就等于给 `git add -f` / 一次改错的 ignore 留了机会。
 *
 * ⚠ 文件不存在时**回退到 debug key**：让 clone 下来的人（以及没配密钥的 CI）照样能出
 * 一个能装的包，而不是构建直接失败。代价是那个包**不能对外发布** —— debug key 的指纹
 * 是公开的（`CN=Android Debug`），任何人都能签出「同包名 + 同签名」的 APK，
 * 而本模块持有 `signature` 级的 `PROCESS_CONTROL` 权限。构建时会打一条警告。
 */
val keystoreProperties = Properties().apply {
  val file = rootProject.file("keystore.properties")
  if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile: File? =
  keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it) }

extensions.configure<ApplicationExtension> {
  namespace = "com.iamcanincan.opticon"
  compileSdk = 37

  buildFeatures {
    compose = true
    // AGP 8+ 起默认不生成 BuildConfig；界面的「关于」与「检查更新」要读 VERSION_NAME，
    // 与其在 strings.xml 里再抄一份版本号，不如让构建来生成唯一真相。
    buildConfig = true
  }

  defaultConfig {
    applicationId = "com.iamcanincan.opticon"
    // 取两个来源模块里更宽的那个：通知侧最低到 Android 8.0（API 26）。
    minSdk = 26
    targetSdk = 37
    versionCode = 3
    versionName = "1.0.3"
  }

  signingConfigs {
    if (releaseStoreFile != null) {
      create("release") {
        storeFile = releaseStoreFile
        storePassword = keystoreProperties.getProperty("storePassword")
        keyAlias = keystoreProperties.getProperty("keyAlias")
        keyPassword = keystoreProperties.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (releaseStoreFile != null) {
        signingConfigs.getByName("release")
      } else {
        logger.warn(
          "keystore.properties 缺失或没写 storeFile —— release 将用 debug key 签名，" +
            "该 APK 不可对外发布（debug key 指纹公开，任何人都能签同签名包）。"
        )
        signingConfigs.getByName("debug")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = false
  }

  lint {
    // 见 app/lint.xml：项目要求 0 warning，被显式忽略的每一条都在文件里写了原因。
    lintConfig = file("lint.xml")
    abortOnError = true
    checkAllWarnings = true
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  // 模块的挂钩逻辑：只在框架注入后运行，编译期不需要打进 APK。
  compileOnly(libs.libxposed.api)

  // 设置界面：Material 3 Expressive（Compose）。版本由 BOM 统一托管。
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.graphics)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons.extended)
  implementation(libs.activity.compose)
  implementation(libs.core.ktx)
  debugImplementation(libs.compose.ui.tooling)
}

// AGP 9.4.1 给 release 构建准备 Compose mapping 时会去要
// org.jetbrains.kotlin:compose-group-mapping:2.2.10 —— 这个版本从未发布过
// （公开仓库里该构件只从 2.3.0 起存在），而且它只去 dl.google.com 找，
// 本机 DNS 解析不了那个域名，release 构建会直接失败。
// 强制到与 Kotlin 版本一致的 2.4.20 即可。
configurations.configureEach {
  if (name == "composeMappingProducerClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:compose-group-mapping:2.4.20")
  }
}
