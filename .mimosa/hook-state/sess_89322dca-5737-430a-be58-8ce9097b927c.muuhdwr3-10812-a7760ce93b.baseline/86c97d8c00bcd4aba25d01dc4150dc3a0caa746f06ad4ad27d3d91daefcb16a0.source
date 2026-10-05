package com.qzkt.timetable.jw.qz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录参数计算。
 *
 * 算法抄自强智 jsxsd 平台登录页里的 `submitForm1()`，
 * 这里用能手算出来的 session 把每一步钉死。
 */
class QzJsxsdLoginTest {

    /**
     * 每位只吃 1 个字符时，encoded 就是"明文字符 + scode 前缀"交替。
     *
     * code = "ab%%%cd"，scode = "ABCDEFGHIJ"，sxh = 20 个 '1'：
     *   a+A  b+B  %+C  %+D  %+E  c+F  d+G
     */
    @Test
    fun `每位吃一个字符时逐字符交替拼接`() {
        val session = "ABCDEFGHIJ#11111111111111111111"
        assertEquals("aAbB%C%D%EcFdG", QzJsxsdLogin.encode("ab", "cd", session))
    }

    /**
     * 第 20 位之后不再混淆，剩下的明文直接接在末尾。
     *
     * 这里故意让账号够长，触发 else 分支。
     */
    @Test
    fun `超过二十位之后原样追加`() {
        val account = "1234567890123456789012345" // 25 位
        val session = "ABCDEFGHIJKLMNOPQRST#11111111111111111111"
        val encoded = QzJsxsdLogin.encode(account, "pw", session)

        val expected = buildString {
            val code = "$account%%%pw"
            for (i in 0 until 20) {
                append(code[i])
                append(session[i]) // scode 的第 i 个字符
            }
            append(code.substring(20))
        }
        assertEquals(expected, encoded)
    }

    @Test
    fun `吃多位时按 sxh 的数字取前缀`() {
        // sxh 前两位是 2 和 0：第一次吃掉 scode 前 2 个字符，第二次吃 0 个
        val session = "ABCDEF#20111111111111111111"
        val encoded = QzJsxsdLogin.encode("x", "y", session)

        // code = "x%%%y"，scode 初始 "ABCDEF"
        // i=0 吃2个 → 'x' + "AB"   → "xAB"，  scode 变 "CDEF"
        // i=1 吃0个 → '%' + ""     → "xAB%"
        // i=2 吃1个 → '%' + "C"    → "xAB%%C"，scode 变 "DEF"
        // i=3 吃1个 → '%' + "D"    → "xAB%%C%D"，scode 变 "EF"
        // i=4 吃1个 → 'y' + "E"    → "xAB%%C%DyE"
        assertEquals("xAB%%C%DyE", encoded)
    }

    @Test
    fun `session 格式不对时返回 null 而不是抛异常`() {
        assertNull(QzJsxsdLogin.encode("u", "p", ""))
        assertNull(QzJsxsdLogin.encode("u", "p", "没有井号"))
        assertNull(QzJsxsdLogin.encode("u", "p", "ABCDEF#"))
        assertNull(QzJsxsdLogin.encode("u", "p", "#1111"))
        assertNull(QzJsxsdLogin.encode("u", "p", "ABC#xy"))
    }

    @Test
    fun `明文按顺序保留在结果里`() {
        val account = "2024001"
        val password = "secret"
        val encoded = QzJsxsdLogin.encode(
            account,
            password,
            "ABCDEFGHIJKLMNOPQRST#11111111111111111111",
        )!!

        // 插入的 scode 片段是混淆项；去掉之后应该能按顺序还原出「学号%%%密码」
        var cursor = 0
        for (ch in "$account%%%$password") {
            val found = encoded.indexOf(ch, cursor)
            assertTrue("字符 '$ch' 应当按顺序出现在结果里", found >= 0)
            cursor = found + 1
        }
    }
}
