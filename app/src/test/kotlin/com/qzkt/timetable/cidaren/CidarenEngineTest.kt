package com.qzkt.timetable.cidaren

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 解析引擎的忠实度测试：拿网页版引擎（js/engine.js）在同一批输入上跑出来的结果当基准，
 * 逐条比对原生移植版的输出（夹具 fixtures.json 由 tools 侧脚本生成）。
 *
 * 比的不只是答案，还有题型、来源、置信度、逐项分析条数、解析块标题、考点与句意文案 ——
 * 任何一处漂移都会在这里暴露出来。
 */
class CidarenEngineTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `解析结果与网页版引擎逐条一致`() {
        val data = CidarenData.fromJson(resource("cidaren/data.json"), resource("cidaren/cet4.json"))
        CidarenEngine.setData(data)
        val fixtures = JSONArray(resource("cidaren/fixtures.json"))

        val problems = ArrayList<String>()
        var checked = 0

        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val id = f.getString("id")
            val r = CidarenEngine.analyze(f.getString("input"))
            checked++

            fun check(field: String, expected: Any?, actual: Any?, ok: Boolean) {
                if (!ok) problems.add("[" + id + "] " + field + "  期望：" + expected + "  实得：" + actual)
            }

            check("ok", f.getBoolean("ok"), r.ok, r.ok == f.getBoolean("ok"))
            check("type", f.getString("type"), r.type, r.type == f.getString("type"))
            check("typeLabel", f.getString("typeLabel"), r.typeLabel, r.typeLabel == f.getString("typeLabel"))
            check("source", f.getString("source"), r.source, r.source == f.getString("source"))
            check("answerIndex", f.getInt("answerIndex"), r.answerIndex, r.answerIndex == f.getInt("answerIndex"))
            check("answerText", f.getString("answerText"), r.answerText, r.answerText == f.getString("answerText"))
            check("confidence", f.getDouble("confidence"), r.confidence, abs(r.confidence - f.getDouble("confidence")) <= 0.011)
            check("optionCount", f.getInt("optionCount"), r.options.size, r.options.size == f.getInt("optionCount"))
            check("analysisCount", f.getInt("analysisCount"), r.analysis.size, r.analysis.size == f.getInt("analysisCount"))

            val head = r.analysis.firstOrNull { it.correct }?.head ?: ""
            check("correctAnalysisHead", f.getString("correctAnalysisHead"), head, head == f.getString("correctAnalysisHead"))

            check("warnings", f.getInt("warnings"), r.warnings.size, r.warnings.size == f.getInt("warnings"))

            val expectedBlocks = f.getJSONArray("blockTitles").let { a -> (0 until a.length()).map { a.getString(it) } }
            val actualBlocks = r.blocks.map { it.title }
            check("blockTitles", expectedBlocks, actualBlocks, expectedBlocks == actualBlocks)

            check("kp", f.getString("kp"), r.kp, r.kp == f.getString("kp"))
            check("structure", f.getString("structure"), r.structure, r.structure == f.getString("structure"))
        }

        val report = java.io.File(System.getProperty("java.io.tmpdir"), "cidaren-engine-diff.txt")
        if (problems.isEmpty()) report.delete()   // 通过时清掉上一次的差异文件，免得看到旧内容误判
        if (problems.isNotEmpty()) {
            report.writeText("与网页版引擎不一致 " + problems.size + " 处 / 共 " + checked + " 条" + System.lineSeparator() + problems.joinToString(System.lineSeparator()))
            println("======== 差异已写入 " + report.absolutePath + " （" + problems.size + " 处）========")
        }
        assertTrue("夹具 " + checked + " 条，不一致 " + problems.size + " 处（详见 stdout）", problems.isEmpty())
    }
}
