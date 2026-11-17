package com.qzkt.timetable.yxy

import com.qzkt.timetable.data.PluginLog
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.Base64
import java.net.HttpCookie
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType

internal data class YxyApiResponse(
    val body: JSONObject,
    val tokenHeader: String?,
)

internal data class YxyTransaction(
    val time: String,
    val address: String,
    val money: String,
)

internal data class YxyElectricityRoom(
    val areaId: String,
    val buildingCode: String,
    val floorCode: String,
    val roomCode: String,
    val displayName: String,
)

internal data class YxyElectricityMeter(
    val mdtype: String,
    val apiName: String,
    val remainingKwh: Double?,
)

internal data class YxyRechargeRecord(
    val datetime: String,
    val money: String,
)

internal data class YxyRechargePage(
    val records: List<YxyRechargeRecord>,
    val hasMore: Boolean,
)

internal data class YxyUsageRecord(
    val datetime: String,
    val usage: String,
)

internal data class YxyUsagePage(
    val records: List<YxyUsageRecord>,
    val hasMore: Boolean,
)

internal class YxyApiException(message: String) : IOException(message)

internal class YxyClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun getSecurityToken(schoolCode: String, deviceId: String): JSONObject {
        val response = post(
            "$COMPUS/common/security/token",
            commonParams(schoolCode, deviceId),
            deviceId,
            sign = false,
        ).body
        ensureSuccess(response)
        return response.optJSONObject("data") ?: throw YxyApiException("获取安全令牌失败：响应缺少 data")
    }

    fun getCaptcha(schoolCode: String, deviceId: String, securityToken: String): String {
        val params = commonParams(schoolCode, deviceId) + ("securityToken" to securityToken)
        val response = post("$COMPUS/common/security/imageCaptcha", params, deviceId).body
        ensureSuccess(response)
        return response.optJSONObject("data")?.optString("img").orEmpty()
    }

    fun sendVerificationCode(
        schoolCode: String,
        deviceId: String,
        phone: String,
        securityToken: String,
        captchaValue: String,
    ) {
        val appSecurityToken = createAppSecurityToken(securityToken, deviceId)
        val params = commonParams(schoolCode, deviceId) + mapOf(
            "mobilePhone" to phone,
            "securityToken" to securityToken,
            "appSecurityToken" to appSecurityToken,
            "sendCount" to "1",
        ) + if (captchaValue.isNotBlank()) {
            mapOf("imageCaptchaValue" to captchaValue.trim())
        } else {
            emptyMap()
        }
        val response = post("$COMPUS/compus/user/sendLoginVerificationCode", params, deviceId).body
        ensureSuccess(response)
    }

    fun login(
        schoolCode: String,
        deviceId: String,
        phone: String,
        verificationCode: String,
    ): YxySession {
        val params = commonParams(schoolCode, deviceId) + mapOf(
            "mobilePhone" to phone,
            // The documented interface uses `code`; some deployments bind this as `verificationCode`.
            "code" to verificationCode,
            "verificationCode" to verificationCode,
            "appAllVersion" to "7.3.6",
            "appPlatform" to "Android",
            "brand" to android.os.Build.BRAND.orEmpty(),
            "clientId" to deviceId,
            "mobileType" to android.os.Build.MODEL.orEmpty(),
            "osType" to "Android",
            "osVersion" to android.os.Build.VERSION.RELEASE.orEmpty(),
        )
        val result = post("$COMPUS/login/doLoginByVerificationCode", params, deviceId)
        ensureSuccess(result.body)
        return sessionFromLoginResponse(result.body, result.tokenHeader, deviceId, phone, schoolCode)
    }

    fun getBalance(schoolCode: String, session: YxySession): String {
        val params = commonParams(schoolCode, session.deviceId) + mapOf(
            "ymId" to session.ymId,
            "walletNo" to "1",
        ) + session.token.takeIf(String::isNotBlank)?.let { mapOf("token" to it) }.orEmpty()
        val body = post("$COMPUS/compus/user/getCardMoney", params, session.deviceId).body
        ensureSuccess(body)
        val data = body.opt("data")
        val value = if (data == null || data == JSONObject.NULL) {
            ""
        } else if (data is JSONObject) {
            data.optString("money").ifBlank { data.optString("cardMoney") }
        } else {
            data.toString().trim('"')
        }
        if (value.isBlank()) throw YxyApiException("余额响应缺少 data")
        return value
    }

    fun getTransactions(
        schoolCode: String,
        session: YxySession,
        date: String,
    ): List<YxyTransaction> {
        require(date.matches(Regex("\\d{8}"))) { "日期请使用 YYYYMMDD 格式" }
        val params = commonParams(schoolCode, session.deviceId) + mapOf(
            "ymId" to session.ymId,
            "queryTime" to date,
        ) + session.token.takeIf(String::isNotBlank)?.let { mapOf("token" to it) }.orEmpty()
        val body = post(
            "$COMPUS/routeauth/auth/route/user/cardQuerynoPage",
            params,
            session.deviceId,
        ).body
        ensureSuccess(body)
        val data = body.optJSONObject("data")
        val rows = body.optJSONArray("rows") ?: data?.optJSONArray("rows") ?: JSONArray()
        return (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            YxyTransaction(
                time = row.optString("time"),
                address = row.optString("address"),
                money = row.optString("money"),
            )
        }
    }

    fun getElectricityRooms(
        schoolCode: String,
        session: YxySession,
        campus: String,
    ): List<YxyElectricityRoom> {
        require(campus == CAMPUS_MGS || campus == CAMPUS_ZHPF) { "请选择有效的电费系统" }
        val cookie = electricityCookie(schoolCode, session)
        val bindType = if (campus == CAMPUS_ZHPF) "3" else "1"
        val body = postApplication(
            "$APPLICATION/app/electric/queryBind",
            JSONObject()
                .put("bindType", bindType)
                .put("platform", "YUNMA_APP"),
            cookie,
            session.deviceId,
        )
        ensureSuccess(body)
        val rows = body.optJSONArray("rows")
            ?: body.optJSONObject("data")?.optJSONArray("rows")
            ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val areaId = row.optString("areaId")
            val buildingCode = row.optString("buildingCode")
            val floorCode = row.optString("floorCode")
            val roomCode = row.optString("roomCode")
            if (listOf(areaId, buildingCode, floorCode, roomCode).any(String::isBlank)) {
                return@mapNotNull null
            }
            YxyElectricityRoom(
                areaId = areaId,
                buildingCode = buildingCode,
                floorCode = floorCode,
                roomCode = roomCode,
                displayName = row.optString("roomName").ifBlank {
                    row.optString("displayRoomName").ifBlank {
                        listOf(areaId, buildingCode, floorCode, roomCode).joinToString("-")
                    }
                },
            )
        }
    }

    fun getElectricityMeters(
        schoolCode: String,
        session: YxySession,
        campus: String,
        room: YxyElectricityRoom,
    ): List<YxyElectricityMeter> {
        require(campus == CAMPUS_MGS || campus == CAMPUS_ZHPF) { "请选择有效的电费系统" }
        val fields = roomFields(room)
        val url = if (campus == CAMPUS_ZHPF) {
            "$APPLICATION/app/electric/queryISIMSRoomSurplus"
        } else {
            "$APPLICATION/app/electric/queryRoomSurplus"
        }
        val body = postApplication(
            url,
            fields,
            electricityCookie(schoolCode, session),
            session.deviceId,
        )
        ensureSuccess(body)
        return parseElectricityMeters(body, campus)
    }

    fun schoolDormPaymentUrl(): String = HDXY_DORM_PAYMENT_URL

    fun getElectricityRechargeRecords(
        schoolCode: String,
        session: YxySession,
        campus: String,
        room: YxyElectricityRoom,
        page: Int,
    ): YxyRechargePage {
        require(campus == CAMPUS_MGS || campus == CAMPUS_ZHPF) { "请选择有效的电费系统" }
        require(page >= 1) { "页码必须大于零" }
        val fields = JSONObject()
            .put("areaId", room.areaId)
            .put("buildingCode", room.buildingCode)
            .put("floorCode", room.floorCode)
            .put("roomCode", room.roomCode)
            .put("platform", "YUNMA_APP")
        val url: String
        if (campus == CAMPUS_ZHPF) {
            fields.put("subType", "100304")
                .put("currentPage", page)
            url = "$APPLICATION/app/electric/queryISIMSRoomBuyRecord"
        } else {
            fields.put("pageNo", page)
                .put("pageSize", RECHARGE_PAGE_SIZE)
            url = "$APPLICATION/app/electric/roomBuyRecord"
        }
        val body = postApplication(
            url,
            fields,
            electricityCookie(schoolCode, session),
            session.deviceId,
        )
        ensureSuccess(body)
        val rows = body.optJSONArray("rows")
            ?: body.optJSONObject("data")?.optJSONArray("rows")
            ?: JSONArray()
        val records = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            YxyRechargeRecord(
                datetime = row.optString("dateTime").ifBlank { row.optString("datetime") },
                money = row.optString("amount").ifBlank { row.optString("money") },
            )
        }
        val total = body.optInt("total", body.optJSONObject("data")?.optInt("total", -1) ?: -1)
        val hasMore = if (total >= 0) page * RECHARGE_PAGE_SIZE < total else records.size >= RECHARGE_PAGE_SIZE
        return YxyRechargePage(records, hasMore)
    }

    fun getElectricityUsageRecords(
        schoolCode: String,
        session: YxySession,
        campus: String,
        room: YxyElectricityRoom,
        page: Int,
        meterType: String? = null,
    ): YxyUsagePage {
        require(campus == CAMPUS_MGS || campus == CAMPUS_ZHPF) { "请选择有效的电费系统" }
        require(page >= 1) { "页码必须大于零" }
        val cookie = electricityCookie(schoolCode, session)
        val fields = roomFields(room)
        val url: String
        if (campus == CAMPUS_ZHPF) {
            fields.put("mdtype", meterType?.takeIf(String::isNotBlank)
                ?: throw YxyApiException("缺少电表类型，无法查询该表计用电明细"))
            url = "$APPLICATION/app/electric/getISIMSRecords"
        } else {
            fields.put("pageNo", page)
                .put("pageSize", RECHARGE_PAGE_SIZE)
            url = "$APPLICATION/app/electric/queryUsageRecord"
        }
        val body = postApplication(url, fields, cookie, session.deviceId)
        ensureSuccess(body)
        val rows = body.optJSONArray("rows")
            ?: body.optJSONObject("data")?.optJSONArray("rows")
            ?: JSONArray()
        val records = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            YxyUsageRecord(
                datetime = row.optString("dateTime").ifBlank { row.optString("datetime") },
                usage = row.optString("dayUsage").ifBlank { row.optString("used") },
            )
        }
        val total = body.optInt("total", body.optJSONObject("data")?.optInt("total", -1) ?: -1)
        val hasMore = campus == CAMPUS_MGS &&
            if (total >= 0) page * RECHARGE_PAGE_SIZE < total else records.size >= RECHARGE_PAGE_SIZE
        return YxyUsagePage(records, hasMore)
    }

    private fun roomFields(room: YxyElectricityRoom): JSONObject = JSONObject()
        .put("areaId", room.areaId)
        .put("buildingCode", room.buildingCode)
        .put("floorCode", room.floorCode)
        .put("roomCode", room.roomCode)
        .put("platform", "YUNMA_APP")

    private fun electricityCookie(schoolCode: String, session: YxySession): String {
        val authorizationUrl = electricityAuthorizationUrl(schoolCode, session)
        val noRedirectClient = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val authCode = noRedirectClient.newCall(
            Request.Builder()
                .url(authorizationUrl)
                .header("User-Agent", userAgent(session.deviceId))
                .get()
                .build(),
        ).execute().use { response ->
            val location = response.header("Location")
                ?: throw YxyApiException("宿舍电费授权失败：没有收到授权跳转地址")
            extractElectricityAuthCode(authorizationUrl, location)
        }
        val exchange = Request.Builder()
            .url("$APPLICATION/app/login/getUser4Authorize")
            .header("User-Agent", userAgent(session.deviceId))
            .post(
                JSONObject()
                    .put("authType", "2")
                    .put("code", authCode)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
        return http.newCall(exchange).execute().use { response ->
            val responseText = response.body.string()
            if (!response.isSuccessful) {
                throw YxyApiException("宿舍电费换取授权失败（HTTP ${response.code}）")
            }
            val result = try {
                JSONObject(responseText)
            } catch (e: org.json.JSONException) {
                throw YxyApiException("宿舍电费授权返回了无法识别的数据")
            }
            ensureSuccess(result)
            response.headers("Set-Cookie")
                .flatMap { HttpCookie.parse(it) }
                .lastOrNull { it.name == "shiroJID" }
                ?.value
                ?.takeIf(String::isNotBlank)
                ?: throw YxyApiException("宿舍电费授权未返回 shiroJID")
        }
    }

    private fun electricityAuthorizationUrl(schoolCode: String, session: YxySession): HttpUrl =
        HttpUrl.Builder()
            .scheme("https")
            .host("auth.xiaofubao.com")
            .addPathSegments("authoriz/getCodeV2")
            .addQueryParameter("bindSkip", "1")
            .addQueryParameter("authType", "2")
            .addQueryParameter("ymAppId", ELECTRICITY_APP_ID)
            .addQueryParameter("callbackUrl", "$APPLICATION/")
            .addQueryParameter("unionid", session.ymId)
            .addQueryParameter("schoolCode", schoolCode)
            .addQueryParameter("ymAuthToken", "")
            .build()

    private fun postApplication(
        url: String,
        fields: JSONObject,
        cookie: String,
        deviceId: String,
    ): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent(deviceId))
            .header("Cookie", "shiroJID=$cookie")
            .post(fields.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body.string()
            PluginLog.i("易校园", "POST " + url.substringAfterLast('/') + " → HTTP " + response.code + "，" + text.length + " 字节")
            if (!response.isSuccessful) {
                PluginLog.e("易校园", "宿舍电费查询失败 HTTP " + response.code)
                throw YxyApiException("宿舍电费查询失败（HTTP ${response.code}）：${text.take(120)}")
            }
            try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw YxyApiException("宿舍电费接口返回了无法识别的数据：${text.take(120)}")
            }
        }
    }

    private fun post(
        url: String,
        params: Map<String, String>,
        deviceId: String,
        sign: Boolean = true,
    ): YxyApiResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent(deviceId))
            .apply { if (sign) header("sign", YxySigner.sign(params)) }
            .post(JSONObject(params).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw YxyApiException("请求失败（HTTP ${response.code}）：${text.take(160)}")
            }
            val body = try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw YxyApiException("服务返回了无法识别的数据：${text.take(120)}")
            }
            YxyApiResponse(body, response.header("token"))
        }
    }

    private fun commonParams(schoolCode: String, deviceId: String): Map<String, String> = mapOf(
        "appVersion" to "730",
        "deviceId" to deviceId,
        "nt" to System.currentTimeMillis().toString(),
        "platform" to "YUNMA_APP",
        "schoolCode" to schoolCode,
    )

    private fun ensureSuccess(body: JSONObject) {
        if (body.optBoolean("success", true).not()) {
            throw YxyApiException(body.optString("message").ifBlank { "请求失败" })
        }
        val statusCode = body.opt("statusCode")?.toString().orEmpty()
        if (statusCode.isNotBlank() && statusCode !in SUCCESS_CODES) {
            throw YxyApiException(body.optString("message").ifBlank { "接口错误（$statusCode）" })
        }
    }

    private fun createAppSecurityToken(securityToken: String, deviceId: String): String {
        require(securityToken.length >= 56) { "安全令牌格式不正确，请重新获取" }
        val key = securityToken.substring(0, 16).toByteArray(StandardCharsets.UTF_8)
        val encodedTime = Base64.getDecoder().decode(securityToken.substring(32, 56))
        val decrypt = Cipher.getInstance("AES/ECB/PKCS5Padding")
        decrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val tokenTime = String(decrypt.doFinal(encodedTime), StandardCharsets.UTF_8).trim()
        val payload = listOf(
            deviceId,
            "YUNMA_APP",
            tokenTime,
            System.currentTimeMillis().toString(),
            "730",
        ).joinToString("|")
        val first = md5(payload).uppercase(Locale.ROOT)
        val checksum = md5(first).uppercase(Locale.ROOT)
        val encrypt = Cipher.getInstance("AES/ECB/PKCS5Padding")
        encrypt.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.getEncoder().encodeToString(
            encrypt.doFinal("$payload|$checksum".toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun userAgent(deviceId: String): String =
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; Android for arm64; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.186 " +
            "Mobile Safari/537.36 ZJYXYwebviewbroswer ZJYXYAndroid " +
            "tourCustomer/yunmaapp.NET/7.3.6/$deviceId"

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    private companion object {
        const val COMPUS = "https://compus.xiaofubao.com"
        const val APPLICATION = "https://application.xiaofubao.com"
        const val HDXY_DORM_PAYMENT_URL = "https://nygl.hdxy.edu.cn/cpp/mobileNew/pay.html"
        const val ELECTRICITY_APP_ID = "1810181825222034"
        const val CAMPUS_MGS = "mgs"
        const val CAMPUS_ZHPF = "zhpf"
        const val RECHARGE_PAGE_SIZE = 30
        val SUCCESS_CODES = setOf("0", "200", "204")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun extractElectricityAuthCode(authUrl: HttpUrl, location: String): String {
    val target = authUrl.resolve(location.replace("#/", ""))
        ?: throw YxyApiException("宿舍电费授权返回了无效跳转地址")
    return target.queryParameter("ymCode")
        ?: throw YxyApiException("宿舍电费授权跳转地址缺少 ymCode")
}

internal fun parseElectricityMeters(body: JSONObject, campus: String): List<YxyElectricityMeter> {
    val data = body.optJSONObject("data") ?: body
    if (campus == "mgs") {
        val remaining = data.firstNumeric("surplus")
        return listOf(
            YxyElectricityMeter(
                mdtype = "room",
                apiName = data.firstText("mdname", "meterName", "displayRoomName").orEmpty(),
                remainingKwh = remaining,
            ),
        )
    }

    require(campus == "zhpf") { "请选择有效的电费系统" }
    val meters = data.optJSONArray("surplusList") ?: JSONArray()
    if (meters.length() == 0) throw YxyApiException("宿舍电费响应没有返回表计列表")
    return (0 until meters.length()).mapNotNull { index ->
        val meter = meters.optJSONObject(index) ?: return@mapNotNull null
        val mdtype = meter.firstText("mdtype", "meterType").orEmpty()
        if (mdtype.isBlank()) return@mapNotNull null
        val apiName = meter.firstText("mdname", "meterName", "displayName", "name").orEmpty()
        val ownSurplus = meter.firstNumeric("surplus")
        YxyElectricityMeter(
            mdtype = mdtype,
            apiName = apiName,
            remainingKwh = ownSurplus,
        )
    }.also { parsed ->
        if (parsed.isEmpty()) throw YxyApiException("宿舍电费响应中的表计类型缺失")
    }
}

private fun JSONObject.firstText(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key ->
        optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

private fun JSONObject.firstNumeric(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { key -> opt(key).toFiniteDouble() }

private fun Any?.toFiniteDouble(): Double? = when (this) {
    is Number -> toDouble().takeIf(Double::isFinite)
    is String -> Regex("""[-+]?\d+(?:\.\d+)?""")
        .find(replace(",", ""))
        ?.value
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
    else -> null
}

internal fun sessionFromLoginResponse(
    body: JSONObject,
    tokenHeader: String?,
    deviceId: String,
    phone: String,
    schoolCode: String,
): YxySession {
    val data = body.optJSONObject("data")
        ?: throw YxyApiException("登录失败：响应缺少 data")
    val ymId = data.optString("id").ifBlank { data.optString("ymId") }
    if (ymId.isBlank()) {
        throw YxyApiException("登录响应缺少用户 id")
    }
    val token = data.optString("token")
        .ifBlank { tokenHeader.orEmpty() }
        .ifBlank { body.optString("token") }
    return YxySession(deviceId, phone, ymId, token, schoolCode)
}

internal object YxySigner {
    fun sign(params: Map<String, String>): String {
        val payload = params.toSortedMap().values.filter(String::isNotEmpty).joinToString("|")
        val deviceId = params["deviceId"].orEmpty()
        val ymId = params["ymId"].orEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((ymId.takeLast(13) + deviceId.takeLast(29)).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val key = digest.substring(32).toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(
            mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
