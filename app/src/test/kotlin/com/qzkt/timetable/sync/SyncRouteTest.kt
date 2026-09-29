package com.qzkt.timetable.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同步方式的选路规则。
 *
 * 这条规则踩过坑：手动刷新和后台定时各写各的判断，结果"有会话就用会话"只接进了后台，
 * 前台刷新还在用账号密码登录 —— 学校有反自动化校验时必然失败，用户点了刷新却什么都刷不出来。
 * 所以把它固定下来，两边只能共用。
 */
class SyncRouteTest {

    @Test
    fun `有会话时优先用会话`() {
        // 有会话就必须用它 —— 这是兼容性最好的一条路，
        // 哪怕同时标着"网页导入"也不能退回去走账号密码
        assertEquals(SyncRoute.SESSION, chooseRoute(hasSession = true, useWebImport = true))
        assertEquals(SyncRoute.SESSION, chooseRoute(hasSession = true, useWebImport = false))
    }

    @Test
    fun `没有会话但有密码时才走账号密码接口`() {
        assertEquals(SyncRoute.PASSWORD, chooseRoute(hasSession = false, useWebImport = false))
    }

    @Test
    fun `课表来自应用内登录但会话没了就没有可用方式`() {
        // 这时不能去试账号密码（很可能根本走不通），也不能报"同步失败"，
        // 只能提示用户重新登录一次
        assertEquals(SyncRoute.UNAVAILABLE, chooseRoute(hasSession = false, useWebImport = true))
    }
}
