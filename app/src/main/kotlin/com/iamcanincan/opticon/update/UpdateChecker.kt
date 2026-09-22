package com.iamcanincan.opticon.update

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONObject

/**
 * 到 GitHub 上看有没有新版本。
 *
 * 这是应用里**唯一**一处联网，而且只在用户主动点「检查更新」时才发起 ——
 * 没有后台轮询、没有上报。挂钩部分（跑在被注入进程里的代码）完全不联网。
 *
 * 只读 `releases/latest`：它天然排除 draft 和 prerelease，正好是「正式发布的
 * 最新版」这个语义，不用自己在一堆 release 里挑。
 *
 * 用 `HttpURLConnection` + `org.json`（都在框架里），不为一个 GET 引入第三方库。
 */
object UpdateChecker {

  /**
   * 检查地址，按顺序试。
   *
   * 第二条是 GitHub 的公共加速镜像。实测（2026-09-20，国内网络）：
   * `api.github.com` 直接 `UnknownHost`（DNS 解析不到），而 `gh-proxy.com`
   * 转发同一路径能正常返回 200 + 完整 JSON。
   * 没有这条回退，国内用户点「检查更新」永远只看到「解析不了域名」。
   *
   * 两条都是只读的公开 API、不带任何凭据。镜像方能看到「有人在查这个仓库的
   * 最新版本」—— 仅此而已，而仓库地址本来就写在应用里。
   */
  private val ENDPOINTS =
    listOf(
      "https://api.github.com/repos/IamCanincan/Opticon/releases/latest",
      "https://gh-proxy.com/https://api.github.com/repos/IamCanincan/Opticon/releases/latest",
    )

  /** GitHub API 不带 User-Agent 会直接回 403，所以必须带一个。 */
  private const val USER_AGENT = "Opticon"

  private const val TIMEOUT_MS = 10_000

  /**
   * 检查结果。
   *
   * 失败也带上原因：用户看到「检查失败」时，得能分清是自己没网、GitHub 限流，
   * 还是仓库压根还没发过 Release —— 这三种的下一步动作完全不同。
   */
  sealed interface Result {

    /** 远端比本机新 */
    data class Newer(val version: String, val url: String) : Result

    /**
     * 已经是最新。
     *
     * 远端版本比本机**旧**也归到这里：那说明本地装的是还没发布出去的版本
     * （比如自己构建的），不该提示用户去「升级」到一个更老的版本。
     */
    data class UpToDate(val version: String) : Result

    /**
     * 仓库还没发布过任何 Release（GitHub 返回 404）。
     *
     * 这是**正常状态**而不是故障 —— 单独成一类，免得界面把它渲染成红色的
     * 「检查失败」，让人以为是网络或代码出了问题。
     */
    data object NoRelease : Result

    data class Failed(val reason: String) : Result
  }

  /**
   * 同步发起一次检查 —— 会阻塞，**必须在工作线程上调用**。
   *
   * 按 [ENDPOINTS] 顺序试，第一个拿到有效响应的就用它；全部失败时报**第一条**
   * （直连）的失败原因 —— 那才是主通道的真实状况。
   *
   * @param currentVersion 本机 versionName，如 "1.0"
   */
  fun check(currentVersion: String): Result {
    var firstFailure: Result.Failed? = null
    for (endpoint in ENDPOINTS) {
      val result = request(endpoint, currentVersion)
      when (result) {
        is Result.Failed -> if (firstFailure == null) firstFailure = result
        else -> return result
      }
    }
    return firstFailure ?: Result.Failed("没有可用的检查地址")
  }

  private fun request(endpoint: String, currentVersion: String): Result = try {
    val connection =
      (URL(endpoint).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        requestMethod = "GET"
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/vnd.github+json")
      }
    try {
      when (val code = connection.responseCode) {
        HttpURLConnection.HTTP_OK -> evaluate(connection, currentVersion)
        404 -> Result.NoRelease
        403 -> Result.Failed("请求被 GitHub 限流了，过一会儿再试")
        else -> Result.Failed("GitHub 返回 HTTP $code")
      }
    } finally {
      connection.disconnect()
    }
  } catch (t: Throwable) {
    Result.Failed(networkReason(t))
  }

  private fun evaluate(connection: HttpURLConnection, currentVersion: String): Result {
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    val json = JSONObject(body)
    val tag = json.optString("tag_name").trim()
    // 发布页地址用响应里的，不自己拼 —— 免得仓库改名后链接失效。
    val url = json.optString("html_url").trim()
    if (tag.isEmpty()) return Result.Failed("响应里没有版本号")

    val remote = parseVersion(tag) ?: return Result.Failed("看不懂远端版本号：$tag")
    val local =
      parseVersion(currentVersion) ?: return Result.Failed("看不懂本机版本号：$currentVersion")

    val shown = tag.removePrefix("v")
    return if (compare(remote, local) > 0) Result.Newer(shown, url) else Result.UpToDate(shown)
  }

  /**
   * 解析 "v1.1" / "1.0" / "1.2-rc1" 这类版本号。
   *
   * 只取点分数字部分，预发布后缀直接丢掉。解析不出来返回 null，让上层报
   * 「看不懂」而不是拿 0 去比出一个错误的结论。
   */
  private fun parseVersion(raw: String): List<Int>? {
    val cleaned = raw.trim().removePrefix("v").substringBefore('-').substringBefore('+')
    if (cleaned.isEmpty()) return null
    val numbers = ArrayList<Int>()
    for (part in cleaned.split('.')) {
      numbers.add(part.toIntOrNull() ?: return null)
    }
    return numbers
  }

  /** 逐段比大小，缺的段按 0 算，所以 "1.1" 和 "1.1.0" 相等 */
  private fun compare(a: List<Int>, b: List<Int>): Int {
    for (index in 0 until maxOf(a.size, b.size)) {
      val diff = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
      if (diff != 0) return diff
    }
    return 0
  }

  /**
   * 把异常翻译成用户能看懂的一句话。
   *
   * 注意顺序：UnknownHost / SocketTimeout / SSL 都是 IOException 的子类，
   * 必须先判它们，否则全被最后那条 IOException 吞掉。
   */
  private fun networkReason(t: Throwable): String =
    when (t) {
      is UnknownHostException -> "解析不了域名，检查网络"
      is SocketTimeoutException -> "连接超时"
      is SSLException -> "TLS 握手失败"
      else -> "网络请求失败（${t.javaClass.simpleName}）"
    }
}