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

    @Test
    fun `kunnu8 模板正文取自 nr1 而不是整个 body`() {
        // kunnu8/luoxia 模板把 id="nr_body" 挂在 <body> 标签上，
        // 抓错容器会把「Ctrl+D 收藏本站」「共 7 条评论」整页带进阅读页
        val html = """
            <html><body id="nr_body" class="lx">
              <div id="pagewrap">
                <header><a href="/">鲲弩小说</a> Ctrl+D 收藏本站</header>
                <div id="mydiv">关灯 护眼 小 中 大 繁 直达底部</div>
                <div id="nr1"><p>正文第一段。</p><p>正文第二段。</p></div>
                <div id="comments">共 7 条评论</div>
                <div>评论被关闭了！</div>
              </div>
            </body></html>
        """.trimIndent()

        val text = Jsoup.parse(html).kunnuContentElement()
            .textWithBreaks()
            .stripSiteJunk()

        assertEquals("正文第一段。\n正文第二段。", text)
    }

    @Test
    fun `真把 nr_body 用作正文 div 的站点也能取到`() {
        val html = """
            <html><body>
              <div>页面其他内容。</div>
              <div id="nr_body"><p>这才是正文。</p></div>
            </body></html>
        """.trimIndent()

        val text = Jsoup.parse(html).kunnuContentElement().textWithBreaks().stripSiteJunk()

        assertEquals("这才是正文。", text)
    }

    @Test
    fun `粘在段落末尾的水印连装饰域名一起抠掉`() {
        // 实测 luoxiadushu.com 的水印形态：正文段落末尾粘着
        // 「落*霞*读*书* 🐱 =- l u o x i a d u s h u . c o m -=」
        val html = """
            <div id="nr_body">
              <p>大伙儿能在这荒山相逢，也是几世修不来的缘分。”他说落*霞*读*书* 🐱 =- l u o x i a d u s h u . c o m -=</p>
              <p>梁文靖面皮一热，抗声道：“爹总说我武艺不好。”</p>
              <p>落*霞*读*书*</p>
            </div>
        """.trimIndent()

        val out = extract(html)

        assertTrue(out.contains("缘分。”他说"))
        assertTrue(out.contains("爹总说我武艺不好。"))
        assertFalse(out.contains("落霞读书"))
        assertFalse(out.contains("luoxia"))
        assertFalse(out.contains("🐱"))
        assertFalse(out.contains("www"))
        // 独立成行的整行水印被删掉后不该留下空行
        assertEquals(2, out.split("\n").size)
    }

    @Test
    fun `鲲弩小说反引号水印同样被抠掉`() {
        val html = """
            <div id="nr_body">
              <p>他说完这句就走了。鲲`弩`小`说 w w w . k u n n u 8 . c o m</p>
              <p>正文不受影响。</p>
            </div>
        """.trimIndent()

        val out = extract(html)

        assertTrue(out.contains("他说完这句就走了。"))
        assertTrue(out.contains("正文不受影响。"))
        assertFalse(out.contains("鲲弩小说"))
        assertFalse(out.contains("kunnu"))
    }
}
