package com.qzkt.timetable.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 设置类页面的通用容器：一张卡片 = 一个功能块。 */
@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
