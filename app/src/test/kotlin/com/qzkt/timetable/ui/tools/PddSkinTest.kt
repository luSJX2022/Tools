package com.qzkt.timetable.ui.tools

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 深色模式的配色测试：浅色必须等于页面 CSS 原值，暗色要满足可读对比度。 */
class PddSkinTest {

    @Test
    fun `浅色配色与页面 CSS 一致`() {
        assertEquals(Color.White, PddLight.bg)
        assertEquals(Color.White, PddLight.card)
        assertEquals(Color(0xFF151516), PddLight.text)
        assertEquals(Color(0xFF58595B), PddLight.sub)
        assertEquals(Color(0xFF9C9C9C), PddLight.muted)
        assertEquals(Color(0xFFE02E24), PddLight.red)
        assertEquals(Color(0xFFE53B43), PddLight.tabActive)
        assertEquals(Color(0xFF0076FF), PddLight.link)
        assertEquals(Color(0xFFF5F5F5), PddLight.identityBg)
    }

    @Test
    fun `深色是深底浅字且文字对比度达标`() {
        val dark = pddColors(true)
        assertSame(PddDark, dark)
        assertTrue("底色应足够深", dark.bg.luminance() < 0.05f)
        assertTrue("卡片应比底色略亮", dark.card.luminance() > dark.bg.luminance())
        assertTrue("正文应够亮", dark.text.luminance() > 0.7f)
        // WCAG 相对亮度对比度：正文 vs 底色应 >= 7（AAA）
        val ratio = (dark.text.luminance() + 0.05f) / (dark.bg.luminance() + 0.05f)
        assertTrue("对比度只有 " + ratio, ratio >= 7f)
        // 次要文字对比度 >= 4.5
        val subRatio = (dark.sub.luminance() + 0.05f) / (dark.bg.luminance() + 0.05f)
        assertTrue("次要文字对比度只有 " + subRatio, subRatio >= 4.5f)
    }

    @Test
    fun `深色下红色文字提亮但按钮仍用品牌红`() {
        val dark = pddColors(true)
        assertTrue("深色下的红字要比品牌红亮", dark.red.luminance() > dark.redSolid.luminance())
        assertEquals(Color(0xFFE02E24), dark.redSolid)
        assertSame(PddLight, pddColors(false))
    }
}
