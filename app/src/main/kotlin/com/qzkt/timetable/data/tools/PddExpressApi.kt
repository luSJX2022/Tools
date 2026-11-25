package com.qzkt.timetable.data.tools

import com.qzkt.timetable.data.net.ipv4First
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import com.qzkt.timetable.data.PluginLog
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 「我的快递」里的一个包裹（对应 package_out_list 的一项）。 */
data class ExpressPackage(
    val station: String,
    val carrier: String,
    val trackingNumber: String,
    val operateTime: String,
    val jumpUrl: String,
    /** 取件码（页面里叫 pick_code，可能带「取件码」前缀和空格，已清洗）。 */
    val pickCode: String = "",
    /** 包裹图片（「近一周已取出」列表用）。 */
    val picUrl: String = "",
)

/** 「我的快递」一次查询的结果。 */
data class ExpressOverview(
    val count: Int,
    /** 待取件（combine_list）。 */
    val packages: List<ExpressPackage>,
    /** 近一周已取出（package_out_list）——已取出的包裹只会出现在这里。 */
    val recentPackages: List<ExpressPackage>,
    /** 顶部横幅文案，例如「你有2个包裹正在运输中」。 */
    val bannerText: String,
    /** 站点里没包裹时，页面会显示「暂无手机尾号 9196 收到的快递」——这里就是那些尾号。 */
    val mobileTails: List<String>,
    /** 「查看我收到的快递」入口的文案与配图（页面里的图片预览区）。 */
    val listPreviewDesc: String,
    val listPreviewImages: List<String>,
    /** 包裹所属站点名（页面顶部展示）。 */
    val stationName: String,
    val fetchedAt: Long,
)

/** 「收件码」一次生成的结果。 */
data class PickupCode(
    val code: String,
    val expireMills: Long,
    /** 例如「已绑定常用手机号：156****9196」。 */
    val mobileDesc: String,
    val fetchedAt: Long,
) {
    /** 剩余有效秒数；已过期返回 0。 */
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long {
        val left = expireMills - (now - fetchedAt)
        return if (left <= 0) 0 else left / 1000
    }

    fun remainingText(now: Long = System.currentTimeMillis()): String {
        val s = remainingSeconds(now)
        return if (s <= 0) "已过期，请重新生成" else "%d:%02d".format(s / 60, s % 60)
    }
}

/** 拼多多用户资料（头像 / 昵称）。 */
data class PddProfile(
    val nickname: String,
    val avatarUrl: String,
    val fetchedAt: Long,
) {
    /** 页面在没登录/拿不到时会显示「代收点用户」，界面按同样规则兜底。 */
    val displayName: String get() = nickname.takeIf { it.isNotBlank() && it != "null" } ?: "代收点用户"
    val hasAvatar: Boolean get() = avatarUrl.isNotBlank() && avatarUrl != "null"
}

/** 拼多多「取快递」接口的鉴权：整段 Cookie（必须含 accesstoken，最好带 pdd_user_id）。 */
data class PddAuth(val cookie: String) {
    /** 登录令牌：网页/App 登录写 PDDAccessToken（大写），微信小程序抓包写 accesstoken，两种都认。 */
    val accessToken: String? = TOKEN_RE.find(cookie)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 用户标识：网页登录是 pdd_user_id / pdd_user_uin，旧抓包是 pdd_uin。 */
    val userId: String? = USER_RE.find(cookie)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    val ready: Boolean get() = accessToken != null && userId != null

    fun requireReady() {
        require(ready) {
            "还没有拼多多登录态：请先登录拼多多，或粘贴含 PDDAccessToken / accesstoken 与 pdd_user_id 的 Cookie"
        }
    }

    companion object {
        private val TOKEN_RE = Regex(
            """(?:^|;\s*)(?:accesstoken|PDDAccessToken|access_token)=([^;]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val USER_RE = Regex(
            """(?:^|;\s*)(?:pdd_user_id|pdd_user_uin|pdd_uid)=([^;]+)""",
            RegexOption.IGNORE_CASE,
        )
        /** 从「Copy as cURL」、整段请求头文本，或直接一段 Cookie 里把 Cookie 抠出来。 */
        fun fromRawText(raw: String): PddAuth {
            val text = raw.trim()
            val cookieLine = Regex("""(?im)^\s*(?:cookie|set-cookie)\s*:\s*(.+)$""").find(text)?.groupValues?.get(1)
            val curlFlag = Regex("""(?:-H|--header)\s+['"]cookie:\s*([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            val picked = cookieLine ?: curlFlag ?: text
            return PddAuth(picked.trim().removeSuffix(";"))
        }
    }
}

/** 拼多多接口返回的错误，message 已经是给人看的说明。 */
class PddApiException(val status: Int, val errorCode: String?, message: String) : IOException(message)

/**
 * 拼多多「取快递」接口的原生实现 —— 对应小程序里的「我的快递」和「收件码」。
 *
 * 数据来源（网页版 js bundle 里的 axios 定义）：
 *   baseURL = https://mobile.yangkeduo.com/proxy/api/
 *   我的快递: POST api/express_trackbox/station/package          body {"entry_source":"24"}
 *   收件码:   POST api/express_trackbox/generate_identification_code body {"entry_source":"WX"}
 *
 * 鉴权用的是拼多多自己的登录 Cookie（pdd_user_id / pdd_uin / accesstoken 那一套）：
 * 在 App 内的 WebView 里用手机号登录拼多多后抓 Cookie 即可，不需要微信，也不走微信 OAuth。
 */
class PddExpressApi(private val client: OkHttpClient = defaultClient()) {

    /** 「我的快递」原始响应（界面缓存原文，断网时还能解析出来展示）。 */
    fun fetchPackagesRaw(auth: PddAuth): String {
        auth.requireReady()
        // 参数照页面源码：entry_source + dynamic_qrcode + 埋点 id；station_code 只有 URL 里带了才传
        val body = JSONObject()
            .put("entry_source", "24")
            .put("dynamic_qrcode", false)
            .put("page_sn", 113702)
            .put("page_el_sn", 10513093)
        return post("api/express_trackbox/station/package", body, auth).toString()
    }

    /** 头像 / 昵称（页面里的 queryNicknameAndAvatar，参数 scene=dsd.usercenter）。 */
    fun fetchProfileRaw(auth: PddAuth): String {
        auth.requireReady()
        return post("api/express_trackbox/base/nickname/query", JSONObject().put("scene", "dsd.usercenter"), auth).toString()
    }

    /** 「收件码」原始响应。 */
    fun generatePickupCodeRaw(auth: PddAuth): String {
        auth.requireReady()
        return post("api/express_trackbox/generate_identification_code", JSONObject().put("entry_source", "WX"), auth).toString()
    }

    private fun post(path: String, body: JSONObject, auth: PddAuth): JSONObject {
        val request = Request.Builder()
            .url(BASE + "/" + path)
            .header("Cookie", auth.cookie)
            // 网页登录的令牌在 PDDAccessToken 里，抓包的在 accesstoken 里，两个头都带上最稳
            .header("accesstoken", auth.accessToken ?: "")
            .header("PDDAccessToken", auth.accessToken ?: "")
            .header("User-Agent", UA)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://mdkd.pinduoduo.com")
            .header("Referer", REFERER)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            PluginLog.e("菜鸟包裹", "请求 " + path + " 失败", e)
            throw PddApiException(0, null, "网络请求失败：" + (e.message ?: "无法连接拼多多接口"))
        }
        response.use { resp ->
            val text = resp.body.string()
            if (resp.isSuccessful) {
                PluginLog.i("菜鸟包裹", path + " → HTTP " + resp.code + "，" + text.length + " 字节")
                return try {
                    JSONObject(text)
                } catch (e: Exception) {
                    PluginLog.e("菜鸟包裹", path + " 返回的不是 JSON", e)
                    throw PddApiException(resp.code, null, "接口返回的不是 JSON：" + text.take(120))
                }
            }
            val mapped = errorFor(resp.code, text)
            PluginLog.w("菜鸟包裹", path + " → HTTP " + resp.code + "：" + mapped.message)
            throw mapped
        }
    }

    companion object {
        const val BASE = "https://mobile.yangkeduo.com/proxy/api"

        /** 与拼多多 H5 的手机浏览器一致：Cookie 来自网页登录，UA 也保持一致，避免被当成异常客户端。 */
        const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/144.0.0.0 Mobile Safari/537.36"

        /** 非微信路由：带 /weixin/ 前缀的页面会被 302 到微信 OAuth，/package 不会。 */
        const val REFERER = "https://mdkd.pinduoduo.com/package?tab=PACKAGE"

        private val JSON_MEDIA = "application/json;charset=UTF-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .ipv4First()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        /** 把接口错误翻成一句话；424/40001 是最常见的「登录态失效或被风控」。 */
        fun errorFor(status: Int, body: String): PddApiException {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val code = json?.optString("error_code")?.takeIf { it.isNotBlank() && it != "null" }
                ?: json?.optString("errorCode")?.takeIf { it.isNotBlank() && it != "null" }
            val message = json?.optString("error_msg")?.takeIf { it.isNotBlank() && it != "null" }
                ?: json?.optString("errorMsg")?.takeIf { it.isNotBlank() && it != "null" }
            val hint = when {
                status == 424 || code == "40001" ->
                    "登录态失效或被风控拦截（HTTP 424 / 40001）：在微信里重新打开「取快递」页面，用 Reqable 抓一次 " +
                        "station/package 请求，把 Cookie 更新到 App 再试。"
                status == 401 || status == 403 -> "没有权限（HTTP $status）：Cookie 可能已过期，请重新抓包更新。"
                status == 0 -> message ?: "网络请求失败"
                else -> "接口返回 HTTP $status" + (if (message != null) "：$message" else "") +
                    (if (code != null) "（error_code $code）" else "")
            }
            return PddApiException(status, code, hint)
        }

        /** 解析「我的快递」响应。 */
        fun parseExpressOverview(text: String, fetchedAt: Long = System.currentTimeMillis()): ExpressOverview {
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw PddApiException(0, null, "「我的快递」返回的不是 JSON：" + text.take(120))
            }
            if (!root.optBoolean("success", true)) {
                throw errorFor(200, text)
            }
            val result = root.optJSONObject("result") ?: JSONObject()

            // 页面主列表读的是 result.combine_list（按取件码/货架分组），package_out_list 只是「近期包裹」。
            // combine_list 的结构各版本不一样，这里递归找带运单号的对象，避免漏包裹。
            // 待取件：combine_list（已取出的包裹不会出现在这里）
            val packages = ArrayList<ExpressPackage>()
            val seen = HashSet<String>()
            collectPackages(result.optJSONArray("combine_list"), packages, seen, 0)

            // 近一周已取出：package_out_list（页面就是用它渲染「近一周已取出快递」）
            val recent = ArrayList<ExpressPackage>()
            val outList = result.optJSONArray("package_out_list")
            if (outList != null) {
                for (i in 0 until outList.length()) {
                    val item = outList.optJSONObject(i) ?: continue
                    addPackage(item, recent, HashSet(), withPic = true)
                }
            }
            // 同一条运单若两边都有，以待取件为准（避免刚放回货架时重复展示）
            val pendingNumbers = packages.map { it.trackingNumber }.toHashSet()
            recent.removeAll { pendingNumbers.contains(it.trackingNumber) }
            val tails = result.optJSONObject("no_package_mobile_info")?.optJSONArray("mobileList")
            val tailsList = ArrayList<String>(tails?.length() ?: 0)
            for (i in 0 until (tails?.length() ?: 0)) {
                val tail = tails?.optString(i).orEmpty()
                if (tail.isNotBlank()) tailsList += tail
            }
            val preview = result.optJSONObject("list_preview")
            val previewImages = ArrayList<String>(3)
            val imagesArray = preview?.optJSONArray("img_list")
            for (i in 0 until (imagesArray?.length() ?: 0)) {
                val url = imagesArray?.optString(i).orEmpty()
                if (url.isNotBlank()) previewImages += url
            }
            return ExpressOverview(
                count = result.optInt("count", packages.size),
                packages = packages,
                recentPackages = recent,
                bannerText = bannerText(result.optJSONObject("top_banner")),
                mobileTails = tailsList,
                listPreviewDesc = preview?.optString("desc").orEmpty(),
                listPreviewImages = previewImages,
                stationName = result.optString("station_name"),
                fetchedAt = fetchedAt,
            )
        }

        private fun bannerText(banner: JSONObject?): String {
            if (banner == null) return ""
            val arr = banner.optJSONArray("main_text") ?: return ""
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optString("text").orEmpty()
                if (t.isNotBlank()) out += t
            }
            return out.joinToString(" ")
        }

        private val TRACKING_KEYS = listOf("tracking_number", "waybill_number", "tracking_no", "shipping_number", "waybill_sn")
        private val CARRIER_KEYS = listOf("shipping_name", "carrier", "company_name", "express_name")
        private val STATION_KEYS = listOf("address", "station_name", "cell_name", "shelf_name", "pickup_address")
        private val TIME_KEYS = listOf("operate_time", "arrive_time", "update_time", "create_time", "receive_time")
        private val PICK_CODE_KEYS = listOf("pick_code", "pickCode", "pickup_code", "pickupCode", "pick_up_code")

        /** 取件码：页面显示前会去掉「取件码」三个字和空格，这里同样清洗。 */
        fun normalizePickCode(raw: String): String =
            raw.replace("取件码", "").replace(Regex("""\s+"""), "").trim()

        private fun firstString(json: JSONObject, keys: List<String>): String {
            keys.forEach { key ->
                val value = json.optString(key)
                if (value.isNotBlank() && value != "null") return value
            }
            return ""
        }

        private fun addPackage(
            item: JSONObject,
            out: MutableList<ExpressPackage>,
            seen: MutableSet<String>,
            withPic: Boolean = false,
        ) {
            val tracking = firstString(item, TRACKING_KEYS)
            if (tracking.isBlank() || !seen.add(tracking)) return
            out += ExpressPackage(
                station = firstString(item, STATION_KEYS),
                carrier = firstString(item, CARRIER_KEYS),
                trackingNumber = tracking,
                operateTime = firstString(item, TIME_KEYS),
                jumpUrl = item.optString("jump_url"),
                pickCode = normalizePickCode(firstString(item, PICK_CODE_KEYS)),
                picUrl = if (withPic) item.optString("pic_url") else "",
            )
        }

        /** 递归扫描 combine_list：任何带运单号的对象都算一个包裹。 */
        private fun collectPackages(node: org.json.JSONArray?, out: MutableList<ExpressPackage>, seen: MutableSet<String>, depth: Int) {
            if (node == null || depth > 6) return
            for (i in 0 until node.length()) {
                when (val item = node.opt(i)) {
                    is JSONObject -> {
                        addPackage(item, out, seen)
                        item.keys().forEach { key ->
                            collectPackages(item.optJSONArray(key), out, seen, depth + 1)
                        }
                    }
                    is org.json.JSONArray -> collectPackages(item, out, seen, depth + 1)
                    else -> Unit
                }
            }
        }

        /** 头像/昵称在 aes_switch=true 时是用固定 key 加密的（页面里同样处理）。 */
        private const val PROFILE_AES_KEY = "Pdd123oadefjikmh"

        private fun maybeDecrypt(value: String, encrypted: Boolean): String {
            if (!encrypted || value.isBlank() || value == "null") return value
            return runCatching {
                val key = javax.crypto.spec.SecretKeySpec(PROFILE_AES_KEY.toByteArray(Charsets.UTF_8), "AES")
                val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key)
                String(cipher.doFinal(java.util.Base64.getDecoder().decode(value)), Charsets.UTF_8)
            }.getOrDefault(value)
        }

        /** 解析头像/昵称响应。 */
        fun parseProfile(text: String, fetchedAt: Long = System.currentTimeMillis()): PddProfile {
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw PddApiException(0, null, "「头像昵称」返回的不是 JSON：" + text.take(120))
            }
            if (!root.optBoolean("success", true)) throw errorFor(200, text)
            val result = root.optJSONObject("result") ?: JSONObject()
            val encrypted = result.optBoolean("aes_switch", false)
            return PddProfile(
                nickname = maybeDecrypt(result.optString("nickname"), encrypted).trim(),
                avatarUrl = maybeDecrypt(result.optString("avatar"), encrypted).trim(),
                fetchedAt = fetchedAt,
            )
        }

        /** 解析「收件码」响应。 */
        fun parsePickupCode(text: String, fetchedAt: Long = System.currentTimeMillis()): PickupCode {
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw PddApiException(0, null, "「收件码」返回的不是 JSON：" + text.take(120))
            }
            if (!root.optBoolean("success", true)) {
                throw errorFor(200, text)
            }
            val result = root.optJSONObject("result")
                ?: throw PddApiException(0, null, "「收件码」响应里没有 result 字段")
            val code = result.optString("identification_code")
            if (code.isBlank()) throw PddApiException(0, null, "「收件码」响应里没有 identification_code")
            return PickupCode(
                code = code,
                expireMills = result.optLong("expire_mills", 0L),
                mobileDesc = result.optJSONObject("mobile_info")?.optString("mobile_info_desc").orEmpty(),
                fetchedAt = fetchedAt,
            )
        }
    }
}
