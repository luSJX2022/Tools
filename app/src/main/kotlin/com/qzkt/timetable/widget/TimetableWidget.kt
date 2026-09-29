package com.qzkt.timetable.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.qzkt.timetable.appContainer
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.model.CourseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

private val BRAND = Color(0xFF1565C0)
private val ON_BRAND = Color(0xFFFFFFFF)
private val CARD = Color(0xFFF3F6FB)
private val MUTED = Color(0xFF6C7278)

/** 最多展示几节课，再多就折叠成一行提示。 */
private const val MAX_ROWS = 6

/**
 * 桌面小组件：显示今天的课。
 *
 * 每次后台同步或手动刷新后由 [TimetableWidgetUpdater] 主动刷新；
 * 系统也会按 `updatePeriodMillis`（最快 30 分钟）兜底重绘。
 */
class TimetableWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.appContainer
        val settings = container.settingsStore.current()
        val today = LocalDate.now()
        val week = container.repository.currentWeek(today)
        val todayClasses = container.repository.sessionsOn(week, today.dayOfWeek.value)
        val hasTimetable = container.repository.snapshot.value.sessions.isNotEmpty()

        provideContent {
            WidgetContent(
                week = week,
                todayClasses = todayClasses,
                settings = settings,
                hasTimetable = hasTimetable,
            )
        }
    }
}

class TimetableWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TimetableWidget()
}

@Composable
private fun WidgetContent(
    week: Int,
    todayClasses: List<CourseSession>,
    settings: AppSettings,
    hasTimetable: Boolean,
) {
    Column(modifier = GlanceModifier.fillMaxSize().background(CARD).padding(10.dp)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = "今天 · 第 $week 周",
                style = TextStyle(color = ColorProvider(BRAND), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = "${todayClasses.size} 节课",
                style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp),
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        when {
            !hasTimetable -> Hint("还没导入课表，打开 App 配置学校账号")
            todayClasses.isEmpty() -> Hint("今天没课")
            else -> Column(modifier = GlanceModifier.fillMaxSize()) {
                todayClasses.take(MAX_ROWS).forEach { session ->
                    CourseRow(session = session, settings = settings)
                    Spacer(modifier = GlanceModifier.height(4.dp))
                }
                if (todayClasses.size > MAX_ROWS) {
                    Text(
                        text = "…还有 ${todayClasses.size - MAX_ROWS} 节",
                        style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp))
}

@Composable
private fun CourseRow(session: CourseSession, settings: AppSettings) {
    val timeText = settings.slotOf(session.startPeriod)?.start.orEmpty()
    val subtitle = listOfNotNull(session.room.ifBlank { null }, session.teacher.ifBlank { null })
        .joinToString(" · ")

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(BRAND)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (timeText.isEmpty()) session.name else "$timeText  ${session.name}",
            style = TextStyle(color = ColorProvider(ON_BRAND), fontSize = 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = TextStyle(color = ColorProvider(ON_BRAND), fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}

/** 主动刷新所有已添加的课表小组件。 */
object TimetableWidgetUpdater {

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { TimetableWidget().updateAll(appContext) }
        }
    }
}
