package com.qzkt.timetable.data.book

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线书正文净化：书站会把脚本、面包屑、字号按钮、上下章导航、广告代码
 * 混进正文容器，正则剥标签会把脚本代码和 HTML 实体残字当成正文。
 * 这里用一段仿 kunnu8「落霞读书」模板的 HTML 验证 [textWithBreaks] 和 [stripSiteJunk]。
 */
class OnlineBookContentTest {

    private fun extract(html: String): String {
        val element = Jsoup.parse(html).selectFirst("#nr_body")!!
        return element.textWithBreaks()
            .replace("『", "").replace("』", "")
            .stripSiteJunk()
    }

    @Test
    fun `脚本面包屑按钮导航广告全被剔除 实体正确反转义 重复标题只留一份`() {
        val html = """
            <div id="nr_body">
              <script>
                var d = document.createElement('iframe');
                document.body.appendChild(d);
                d.src = 'https://example.com/ads';
              </script>
              <h1>第一章 生日宴会</h1>
              <h1>第一章 生日宴会</h1>
              <p>首页&gt; 魔戒&gt;魔戒1: 魔戒再现</p>
              <p>关灯 护眼 小 中 大 繁 直达底部</p>
              <p>天下精灵铸三戒，</p>
              <p>地底矮人得七戒，</p>
              <p>adsbygoogle = window.adsbygoogle || []).push({});</p>
              <p>下一章: 灰色的平原</p>
              <p>下一章: 灰色的平原</p>
              <script>window.onload = function() { if (document.readyState == 'complete') {} }</script>
            </div>
        """.trimIndent()

        val out = extract(html)
        val lines = out.split("\n")

        assertEquals(listOf("第一章 生日宴会", "天下精灵铸三戒，", "地底矮人得七戒，"), lines)
    }

    @Test
    fun `正文诗句和章节内容不被误伤`() {
        val html = """
            <div id="nr_body">
              <p>天下精灵铸三戒，</p>
              <p>天下精灵铸三戒，</p>
              <p>地底矮人得七戒，</p>
              <p>寿定凡人持九戒；</p>
              <p>魔多翳影，王座乌青，</p>
              <p>黑暗魔君执其尊。</p>
            </div>
        """.trimIndent()

        val out = extract(html)

        // 相邻但内容不同的行全部保留
        assertEquals(
            "天下精灵铸三戒，\n地底矮人得七戒，\n寿定凡人持九戒；\n魔多翳影，王座乌青，\n黑暗魔君执其尊。",
            out,
        )
    }

    @Test
    fun `script 内容不残留任何 JS 关键字`() {
        val html = """
            <div id="nr_body">
              <p>第一章 生日宴会</p>
              <script>var s = document.createElement('script'); s.async = true;</script>
              <p>正文一行。</p>
            </div>
        """.trimIndent()

        val out = extract(html)

        assertFalse(out.contains("document"))
        assertFalse(out.contains("createElement"))
        assertFalse(out.contains("script"))
        assertTrue(out.contains("正文一行。"))
    }

    @Test
    fun `实体与 nbsp 正确反转义`() {
        val html = """
            <div id="nr_body">
              <p>汤姆&middot;庞巴迪&nbsp;&mdash;&mdash;去去就来</p>
            </div>
        """.trimIndent()

        val out = extract(html)

        assertTrue(out.contains("·"))
        assertFalse(out.contains("&"))
    }
}
