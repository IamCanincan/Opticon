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
      md.update(Proj.codeOnly(f.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8))
      md.update(0.toByte())
    }
    return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(16)
  }

  /**
   * 源码 → **只含代码**的投影：剥掉注释、行尾空白与空行，其余一个字符不动。
   *
   * 六态状态机：正常 / 行注释 / 块注释 / 字符串 / 三引号原始字符串 / 字符字面量。
   * 只有"正常"态里斜线星号那一对才开注释 —— 字符串与字符里的同类符号原样保留，
   * 否则 URL 里的双斜线会把后半行吃掉，那正是这条投影最要命的坏法。
   * 注释换成**一个空格**而不是直接删掉：两个标识符之间的空注释若直接粘成一个词，
   * 会伪造出一个新标识符。
   *
   * ⚠ 这份 KDoc 刻意不写那几种注释符号本身：Gradle 的 .kts 解析器读到块注释里出现
   *    星号加斜线会提前闭合（2026-11-16 亲身踩过一次，报的是"Missing '}'"）。
   *    判据在 `checkPipelineHash`：每份真源码追加注释后哈希不变、追加代码后必须变。
   */
  object Proj {
    fun codeOnly(src: String): String {
      val sb = StringBuilder(src.length)
      var i = 0
      var st = 0
      fun at(k: Int): Char = if (k < src.length) src[k] else '\u0000'
      while (i < src.length) {
        val c = src[i]
        when (st) {
          0 -> when {
            c == '/' && at(i + 1) == '/' -> { st = 1; i += 2; sb.append(' ') }
            c == '/' && at(i + 1) == '*' -> { st = 2; i += 2; sb.append(' ') }
            c == '"' && at(i + 1) == '"' && at(i + 2) == '"' -> { st = 4; sb.append("\"\"\""); i += 3 }
            c == '"' -> { st = 3; sb.append(c); i++ }
            c == '\'' -> { st = 5; sb.append(c); i++ }
            else -> { sb.append(c); i++ }
          }
          1 -> if (c == '\n') { st = 0; sb.append('\n'); i++ } else i++
          2 -> if (c == '*' && at(i + 1) == '/') { st = 0; i += 2 } else { if (c == '\n') sb.append('\n'); i++ }
          3 -> {
            sb.append(c)
            if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 }
            else { if (c == '"') st = 0; i++ }
          }
          4 -> if (c == '"' && at(i + 1) == '"' && at(i + 2) == '"') { sb.append("\"\"\""); i += 3; st = 0 }
          else { sb.append(c); i++ }
          else -> {
            sb.append(c)
            if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 }
            else { if (c == '\'') st = 0; i++ }
          }
        }
      }
      return sb.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
  }
}

val iconPipelineHash = providers.of(IconPipelineHash::class.java) {
  parameters.sources.from(
    fileTree("src/main/kotlin/com/iamcanincan/opticon/circle") { include("*.kt") }
  )
}

/**
 * 上面那套投影的自证台：`./gradlew checkPipelineHash`。
 *
 * 投影只决定**缓存什么时候失效**：跑错了不会崩、不会报错，只会让你在下一次改算法之后
 * 继续吃旧掩码。所以必须有一条能当场变红的判据，而不是"编得过就行"。
 *
 * 两类样本：
 *  1. 合成 —— 注释与空行不影响投影；字符串 / 字符 / 三引号里的注释符号不是注释。
 *  2. 真源码逐份 —— 追加注释后哈希不变、追加代码后哈希必须变。后者是"状态机卡进注释态、
 *     后面整份被吃掉"的探针：一旦发生，追加的代码也不会改变哈希，这一条当场红。
 */
val checkPipelineHash = tasks.register("checkPipelineHash") {
  val srcs = fileTree("src/main/kotlin/com/iamcanincan/opticon/circle") { include("*.kt") }
  inputs.files(srcs)
  doLast {
    fun c(s: String) = IconPipelineHash.Proj.codeOnly(s)
    fun same(a: String, b: String, why: String) =
      check(c(a) == c(b)) { "应视为同一份代码：$why\n--- A ---\n${c(a)}\n--- B ---\n${c(b)}" }
    fun differ(a: String, b: String, why: String) =
      check(c(a) != c(b)) { "必须视为不同代码：$why" }

    same("val a = 1 // tail\n", "val a = 1", "行注释")
    same("/** doc */\nval a = 1\n", "val a = 1", "块注释 / KDoc")
    same("val a = 1 /* mid */\n\n\nval b = 2", "val a = 1\nval b = 2", "行中块注释与空行")
    differ("val s = \"http://x\"", "val s = \"http://y\"", "字符串里双斜线之后仍是代码")
    differ("val s = \"/*a*/\"", "val s = \"/*b*/\"", "字符串里的注释符号不是注释")
    differ("val s = \"a\\\"//b\" + 1", "val s = \"a\\\"//b\" + 2", "转义引号后面的双斜线仍在字符串里")
    differ("val c = '/'", "val c = '*'", "字符字面量不是注释起点")
    same("val c = '/'\n// note\n", "val c = '/'", "字符字面量后面接行注释照常剥")
    differ("val s = \"\"\"a // b\"\"\"", "val s = \"\"\"a // c\"\"\"", "三引号原始字符串里的双斜线不算注释")

    var n = 0
    for (f in srcs.files.sortedBy { it.name }) {
      val raw = f.readText(Charsets.UTF_8)
      val base = c(raw)
      // ⚠ 不能用"代码占比"当判据：`IconLabel.kt` 本来就有七成是 KDoc（363/1303），
      //   那是最先被我自己的阈值打红的一份。改成**结构**判据：头还在、尾还在。
      check(base.length >= 40 && base.contains("package")) {
        "${f.name} 投影只剩 ${base.length} 字符 / 没有 package 行 —— 十有八九把代码当注释吃掉了"
      }
      same(raw + "\n// 追加注释\n/** 追加 KDoc */\n", raw, "${f.name} 追加注释")
      differ(raw, raw + "\nprivate const val __hashProbe = 1\n", "${f.name} 追加代码")
      n++
    }
    check(n > 10) { "只读到 $n 份源码 —— 路径或过滤器坏了（期望 circle/*.kt 全量）" }
    println("checkPipelineHash: 合成样本 9 条 + 真源码 $n 份，全过")
  }
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
    versionCode = 13
    versionName = "1.1.9"
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
