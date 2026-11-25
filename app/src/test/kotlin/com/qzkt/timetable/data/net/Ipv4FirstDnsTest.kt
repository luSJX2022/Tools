package com.qzkt.timetable.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * IPv4 优先排序：只调整尝试顺序，不丢地址。
 *
 * 线上问题：`bfzyapi.com` 有 AAAA 记录而手机网络连不上 IPv6，
 * OkHttp 先试 IPv6 会一直撞 connectTimeout，表现为「影视页一直转圈」。
 */
class Ipv4FirstDnsTest {

    private fun v4(last: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(10, 0, 0, last.toByte()))

    private fun v6(last: Int): InetAddress =
        InetAddress.getByAddress(ByteArray(16).also { it[15] = last.toByte() })

    @Test
    fun `IPv6 排在前面时会被挪到后面`() {
        val six = v6(1)
        val four = v4(1)

        val ordered = Ipv4FirstDns.order(listOf(six, four))

        assertEquals(listOf(four, six), ordered)
        assertTrue(ordered.first() is Inet4Address)
        assertTrue(ordered.last() is Inet6Address)
    }

    @Test
    fun `多个地址时 IPv4 全部提前且各自保持原顺序`() {
        val sixA = v6(1)
        val fourA = v4(1)
        val sixB = v6(2)
        val fourB = v4(2)

        val ordered = Ipv4FirstDns.order(listOf(sixA, fourA, sixB, fourB))

        assertEquals(listOf(fourA, fourB, sixA, sixB), ordered)
    }

    @Test
    fun `IPv6 一个都不丢`() {
        val input = listOf(v6(1), v4(1), v6(2), v4(2), v6(3))

        val ordered = Ipv4FirstDns.order(input)

        assertEquals(input.size, ordered.size)
        assertEquals(input.toSet(), ordered.toSet())
    }

    @Test
    fun `已经是有序时原样返回同一个列表`() {
        val input = listOf(v4(1), v4(2))
        assertSame(input, Ipv4FirstDns.order(input))
    }

    @Test
    fun `纯 IPv6 环境不受影响`() {
        // 纯 IPv6 网络（没有 A 记录）时不能把地址全丢了
        val input = listOf(v6(1), v6(2))
        assertSame(input, Ipv4FirstDns.order(input))
    }

    @Test
    fun `单地址直接返回`() {
        val input = listOf(v4(7))
        assertSame(input, Ipv4FirstDns.order(input))
    }

    @Test
    fun `空列表不炸`() {
        assertEquals(emptyList<InetAddress>(), Ipv4FirstDns.order(emptyList()))
    }

    @Test
    fun `排序后 IPv4 一定在第一位`() {
        val ordered = Ipv4FirstDns.order(listOf(v6(9), v6(8), v4(3)))
        assertEquals("10.0.0.3", ordered.first().hostAddress)
    }
}
