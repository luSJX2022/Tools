package com.qzkt.timetable.data.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 优先 IPv4 的 DNS。
 *
 * 线上现象：影视源 `bfzyapi.com` 同时有 A 记录和 AAAA 记录，日志里反复出现
 * `bfzyapi.com/[2a03:2880:...]:443` 连接超时。原因是 OkHttp 默认按 DNS 返回的顺序
 * 逐个尝试，而手机网络（校园网 / 运营商）上的 IPv6 经常「能解析、连不上」，
 * 于是每次都先撞满 connectTimeout(15s) 才回落 IPv4，用户看到的就是「一直转圈」。
 *
 * 这里只调整**尝试顺序**：A 记录排前面，IPv6 地址一个都不丢。IPv4 不通时
 * OkHttp 5 的 fastFallback（默认开启）会并发去试后面的 IPv6，纯 IPv6 网络不受影响。
 */
object Ipv4FirstDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> =
        order(Dns.SYSTEM.lookup(hostname))

    /**
     * 稳定排序：IPv4 在前，IPv6 保持原顺序跟在后面。
     *
     * 纯函数，单测直接调，不碰网络。
     */
    internal fun order(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size < 2) return addresses
        val ipv4 = ArrayList<InetAddress>(addresses.size)
        val others = ArrayList<InetAddress>(addresses.size)
        for (address in addresses) {
            if (address is Inet4Address) ipv4 += address else others += address
        }
        // 全是 IPv4 或全是 IPv6 时原样返回，免得白白新建列表
        if (ipv4.isEmpty() || others.isEmpty()) return addresses
        return ipv4 + others
    }
}

/**
 * 给 OkHttp 客户端装上 [Ipv4FirstDns]。
 *
 * 所有出网客户端都要调一下：OkHttp 的 DNS 是**每个 Client 各自的属性**，
 * 没有全局开关，漏掉一个，那个功能还是会被 IPv6 拖满一次 connectTimeout。
 */
fun OkHttpClient.Builder.ipv4First(): OkHttpClient.Builder = dns(Ipv4FirstDns)
