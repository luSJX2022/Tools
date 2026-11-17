package com.qzkt.timetable.ui.tools

import androidx.compose.runtime.Composable

/**
 * 菜鸟包裹入口。
 *
 * 界面已按拼多多「取快递」小程序 1:1 原生复刻（见 [PddExpressScreen]）：
 * 取快递 / 身份码 / 个人中心三个页签 + 底部 tab 栏，颜色、字号、图标、文案都取自页面本身。
 * 这里保留旧入口名，路由不用改。
 */
@Composable
fun ParcelPickupScreen(onBack: () -> Unit) {
    PddExpressScreen(onBack = onBack)
}
