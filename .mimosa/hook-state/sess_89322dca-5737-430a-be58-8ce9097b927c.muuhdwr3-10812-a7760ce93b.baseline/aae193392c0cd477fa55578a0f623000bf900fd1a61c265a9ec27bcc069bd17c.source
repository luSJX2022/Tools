package com.qzkt.timetable.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查更新：读 GitHub Releases 的最新版本，和当前版本号比较。
 *
 * 发新版的方式：在 [REPO] 仓库发一个 Release，tag 形如 `v1.0.1`（带不带 v 都认），
 * 想让应用内直接下载就往 Release 里传一个 .apk 附件；不传则只能跳浏览器打开发布页。
 */
object UpdateChecker {

    /** 更新检查指向的 GitHub 仓库。 */
    const val REPO = "luSJX2022/Tools"

    /** 一个已发布的版本。 */
    data class Release(
        /** 归一化后的版本号（去掉 v 前缀），如 "1.0.1"。 */
        val version: String,
        /** Release 标题。 */
        val title: String,
        /** 更新说明（Release 的 body）。 */
        val notes: String,
        /** Release 附件里的 apk 直链；没传附件时为 null（退回发布页）。 */
        val apkUrl: String?,
        /** 发布页地址。 */
        val pageUrl: String,
    )

    /** 检查结果。 */
    sealed interface CheckResult {
        data class UpToDate(val currentVersion: String) : CheckResult
        data class NewVersion(val currentVersion: String, val release: Release) : CheckResult
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun check(currentVersion: String): Result<CheckResult> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "qzkt-app")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) error("仓库还没有发布过版本")
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body?.string() ?: error("空响应")
                val json = JSONObject(body)
                val tag = json.optString("tag_name").trim()
                check(tag.isNotEmpty()) { "发布信息里没有版本号" }
                val release = Release(
                    version = tag.removePrefix("v").removePrefix("V"),
                    title = json.optString("name").ifBlank { tag },
                    notes = json.optString("body").trim(),
                    apkUrl = json.optJSONArray("assets")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val asset = arr.optJSONObject(i) ?: return@mapNotNull null
                            asset.optString("browser_download_url").takeIf { it.endsWith(".apk", true) }
                        }.firstOrNull()
                    },
                    pageUrl = json.optString("html_url"),
                )
                if (isNewer(release.version, currentVersion)) {
                    CheckResult.NewVersion(currentVersion, release)
                } else {
                    CheckResult.UpToDate(currentVersion)
                }
            }
        }
    }

    /**
     * 逐段比较版本号（1.2.10 > 1.2.9），段数不齐按 0 补；带 v / V 前缀和后缀修饰
     * （如 `v1.1-beta`）的段比较时按 0 处理，只影响相等时的判断，不影响主版本比较。
     */
    fun isNewer(remote: String, current: String): Boolean {
        val remoteParts = remote.removePrefix("v").removePrefix("V").split('.').map { it.trim().toIntOrNull() ?: 0 }
        val currentParts = current.removePrefix("v").removePrefix("V").split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(index) { 0 }
            val c = currentParts.getOrElse(index) { 0 }
            if (r != c) return r > c
        }
        return false
    }
}
