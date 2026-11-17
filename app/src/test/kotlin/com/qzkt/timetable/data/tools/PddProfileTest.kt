package com.qzkt.timetable.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** 头像 / 昵称解析的测试。夹具来自抓包的真实响应（昵称「成羽」）。 */
class PddProfileTest {

    @Test
    fun `解析真实响应：昵称与头像`() {
        val raw = """{"success":true,"error_code":"0","error_msg":"成功","result":{"avatar":"https://avatar3.pddpic.com/a/Q0dYbnp1bStEc1NabGE2OXR3VHBuckp6Qng0RENqZWdiZz09djA0-1793374107?imageMogr2/thumbnail/100x","nickname":"成羽"},"http_code":200,"error_message":"成功"}"""
        val profile = PddExpressApi.parseProfile(raw, 1000L)
        assertEquals("成羽", profile.nickname)
        assertEquals("成羽", profile.displayName)
        assertTrue(profile.hasAvatar)
        assertTrue(profile.avatarUrl.startsWith("https://avatar3.pddpic.com/"))
        assertEquals(1000L, profile.fetchedAt)
    }

    @Test
    fun `没有昵称头像时退回代收点用户`() {
        val empty = PddExpressApi.parseProfile("""{"success":true,"result":{"nickname":"","avatar":"null"}}""")
        assertEquals("代收点用户", empty.displayName)
        assertFalse(empty.hasAvatar)

        val nul = PddExpressApi.parseProfile("""{"success":true,"result":{"nickname":"null","avatar":"null"}}""")
        assertEquals("代收点用户", nul.displayName)
    }

    @Test
    fun `aes_switch 打开时能解密昵称`() {
        // 用页面同样的 AES/ECB + 固定 key 加密一份，验证我们能解回来
        val key = SecretKeySpec("Pdd123oadefjikmh".toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = java.util.Base64.getEncoder().encodeToString(cipher.doFinal("成羽".toByteArray()))
        val raw = """{"success":true,"result":{"aes_switch":true,"nickname":"$encrypted","avatar":"null"}}"""
        val profile = PddExpressApi.parseProfile(raw)
        assertEquals("成羽", profile.nickname)
    }

    @Test
    fun `接口失败时抛异常`() {
        val error = runCatching { PddExpressApi.parseProfile("""{"success":false,"error_code":40001}""") }.exceptionOrNull()
        assertTrue(error is PddApiException)
    }
}
