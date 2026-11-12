import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.security.MessageDigest
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

/**
 * **图标管线源码指纹** —— 掩码缓存的失效键（见 `MonoCache.dir`）。
 *
 * 为什么不是装机时间（2026-11-08 真机账）：装机身份每装一次都动，而绝大多数装包只改
 * 界面/文案，图标管线一个字节没变 —— 于是只改界面的更新也要把 243 张掩码全部重算
 * （冷 46.9 s vs 热 22.8 s，差 24.1 s 全是冷合成）。指纹只在整个 `circle/` 包的源码
 * 变了才换：交付掩码是这一层所有代码的函数（模型 / 几何 / 极性 / 盘雾 / 键），所以
 * 覆盖**整个包**，漏一份就会在改过之后继续发旧掩码；多算一条注释的代价只是"多冷一次"，
 * 安全方向。
 *
 * 用 [ValueSource] 而不是在配置期直接读文件：配置缓存（configuration cache）打开后，
 * 后者不会因源码变化而失效，可能拿旧指纹复用旧掩码 —— 那正是这个键要防的事。
 */
abstract class IconPipelineHash : ValueSource<String, IconPipelineHash.Params> {
  interface Params : ValueSourceParameters {
    val sources: ConfigurableFileCollection
  }

  override fun obtain(): String {
    val md = MessageDigest.getInstance("SHA-256")
    // 目录是平铺的（`circle/*.kt`），按文件名排序即可；**不许把绝对路径折进来**，
    // 否则换一台机器/换一个检出目录，同一份源码会得到不同指纹。
    for (f in parameters.sources.files.sortedBy { it.name }) {
      md.update(f.name.toByteArray(Charsets.UTF_8))
      md.update(0.toByte())
      md.update(f.readBytes())
      md.update(0.toByte())
    }
    return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(16)
  }
}

val iconPipelineHash = providers.of(IconPipelineHash::class.java) {
  parameters.sources.from(
    fileTree("src/main/kotlin/com/iamcanincan/opticon/circle") { include("*.kt") }
  )
}

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
    versionCode = 11
    versionName = "1.1.7"
    // 图标管线指纹（见 IconPipelineHash）：写进 BuildConfig，宿主进程里读它决定
    // 掩码缓存目录与「这批图标是谁画的」戳记。值取的是**源码**的哈希，所以 Gradle
    // 会把它当 BuildConfig 的输入 —— 管线一变，生成任务就重跑。
    buildConfigField("String", "MASK_ALGO", "\"${iconPipelineHash.get()}\"")
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
