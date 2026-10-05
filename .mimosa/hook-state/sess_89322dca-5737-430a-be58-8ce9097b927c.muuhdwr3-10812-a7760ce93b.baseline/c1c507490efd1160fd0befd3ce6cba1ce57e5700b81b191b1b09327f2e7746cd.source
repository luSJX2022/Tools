package com.qzkt.timetable.jw.qz

import com.qzkt.timetable.jw.JwAdapter
import com.qzkt.timetable.jw.JwConfig
import com.qzkt.timetable.jw.JwException
import com.qzkt.timetable.jw.JwSession

/**
 * 在强智的两个平台之间自动选择。
 *
 * 强智不同代的产品接口完全不同：
 * - **jsxsd 网页版**（`/jsxsd/...`）：几乎每所学校都有，登录走 `Logon.do` + `LoginToXk`
 * - **app.do 移动端接口**：只有一部分学校开放，返回 JSON
 *
 * 所以先按地址判断（填了含 `jsxsd` 的地址就直接走网页版），
 * 判不出来再按"哪个能连上"依次试，并且**不把第一次的失败抛给用户**——
 * 那是探测过程，不是最终结果。
 */
class SmartQzAdapter(
    private val jsxsd: JwAdapter = QzJsxsdAdapter(),
    private val appDo: JwAdapter = QzAppDoAdapter(),
) : JwAdapter {

    override val id: String = "qz-auto"
    override val displayName: String = "强智（自动选择接口）"

    override suspend fun connect(config: JwConfig): JwSession {
        val candidates = if (QzJsxsdAdapter.looksLikeJsxsd(config.baseUrl)) {
            listOf(jsxsd, appDo)
        } else {
            listOf(appDo, jsxsd)
        }

        var lastError: JwException? = null
        for (candidate in candidates) {
            try {
                return candidate.connect(config)
            } catch (e: JwException) {
                // 只有"网络不通"和"这个接口在这儿不存在"才值得换另一个平台；
                // 网关拦截、账号密码错换过去也一样，直接报给用户
                if (!e.worthRetryingElsewhere) throw e
                lastError = e
            }
        }

        throw lastError ?: JwException("连接失败，请检查学校地址")
    }
}
