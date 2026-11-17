package com.qzkt.timetable.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CODE128 编码器（Code Set B），用于把收件码画成可扫描的条形码。
 *
 * 小程序的「身份码」页面就是用 JsBarcode 的 CODE128 在前端画的，接口只回字符串
 * （identification_code），所以原生这边自己编码；Code Set B 覆盖 ASCII 32–126，
 * 收件码是 wx+数字，完全够用，扫出来的内容和微信里的一致。
 */
object Code128 {

    /** 107 个符号的模块宽度表（每位是「条/空」交替的宽度），下标即符号值。 */
    private val PATTERNS = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112",
    )

    private const val START_B = 104
    private const val STOP = 106
    private const val QUIET_ZONE = 10

    /** 是否所有字符都在 Code Set B 能表示的范围内（ASCII 32–126）。 */
    fun canEncode(data: String): Boolean =
        data.isNotEmpty() && data.all { it.code in 32..126 }

    /**
     * 编码成模块序列：true=条、false=空。左右各带 10 模块静区，
     * 直接按模块宽度画就行（静区不算进条码本身，但扫描器需要它）。
     */
    fun encode(data: String): BooleanArray {
        require(canEncode(data)) { "收件码含有 CODE128 不支持字符（只支持 ASCII 32–126）" }
        val symbols = ArrayList<Int>(data.length + 3)
        symbols.add(START_B)
        var checksum = START_B
        data.forEachIndexed { index, ch ->
            val value = ch.code - 32
            symbols.add(value)
            checksum += value * (index + 1)
        }
        symbols.add(checksum % 103)
        symbols.add(STOP)

        val modules = ArrayList<Boolean>(symbols.size * 11 + QUIET_ZONE * 2)
        repeat(QUIET_ZONE) { modules.add(false) }
        symbols.forEach { symbol ->
            PATTERNS[symbol].forEachIndexed { index, digit ->
                val width = digit - '0'
                val isBar = index % 2 == 0
                repeat(width) { modules.add(isBar) }
            }
        }
        repeat(QUIET_ZONE) { modules.add(false) }
        return modules.toBooleanArray()
    }
}

/**
 * 画一个 CODE128 条形码。白底黑条：放在卡片里必须给白底，否则深色模式下扫不出来。
 */
@Composable
fun Code128Barcode(
    data: String,
    modifier: Modifier = Modifier,
    height: Dp = 88.dp,
    barColor: Color = Color.Black,
) {
    val modules = remember(data) {
        runCatching { Code128.encode(data) }.getOrNull()
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(Color.White)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        modules?.let { drawModules(it, barColor) }
    }
}

private fun DrawScope.drawModules(modules: BooleanArray, barColor: Color) {
    if (modules.isEmpty()) return
    val moduleWidth = size.width / modules.size
    var index = 0
    while (index < modules.size) {
        if (!modules[index]) { index++; continue }
        val start = index
        while (index < modules.size && modules[index]) index++
        // 相邻的「条」合成一个矩形，避免逐模块绘制出现发丝缝
        drawRect(
            color = barColor,
            topLeft = androidx.compose.ui.geometry.Offset(start * moduleWidth, 0f),
            size = androidx.compose.ui.geometry.Size((index - start) * moduleWidth, size.height),
        )
    }
}
