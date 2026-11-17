package com.qzkt.timetable.data.tools

import android.webkit.CookieManager
import android.webkit.WebStorage

private val parcelPickupOrigins = listOf(
    "https://mdkd.pinduoduo.com",
    "https://open.weixin.qq.com",
    "https://weixin.qq.com",
)

fun clearParcelPickupWebData() {
    val cookieManager = CookieManager.getInstance()
    parcelPickupOrigins.forEach { origin ->
        val cookies = cookieManager.getCookie("$origin/").orEmpty()
        cookies.split(';')
            .mapNotNull { cookie -> cookie.trim().substringBefore('=').takeIf(String::isNotBlank) }
            .distinct()
            .forEach { name ->
                cookieManager.setCookie("$origin/", "$name=; Max-Age=0; Path=/")
            }
        WebStorage.getInstance().deleteOrigin(origin)
    }
    cookieManager.flush()
}
