package com.qzkt.timetable.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qzkt.timetable.R
import kotlinx.coroutines.delay

/**
 * 开屏动画：应用图标缩放浮现，名称淡入，停约一秒半后回调结束（外层负责淡出）。
 *
 * 用「打开方式」直接进播放器时不走这段（调用方决定）。
 */
/** 前景图里图形占可见区 50.4%，开屏时放大 2.6 倍正好填满。 */
private const val LOGO_ZOOM = 2.6f

@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        started = true
        delay(1500)
        onFinished()
    }

    val scale by animateFloatAsState(
        targetValue = if (started) 1f else 0.55f,
        animationSpec = tween(500),
        label = "splashScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(500),
        label = "splashAlpha",
    )

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        // 前景图里图形只占可见区的一半（那是启动器图标的比例，四周留白是给蒙版裁的），
                        // 开屏这里把它放大到接近满框，否则 LOGO 会小得可怜。
                        scaleX = scale * LOGO_ZOOM
                        scaleY = scale * LOGO_ZOOM
                    },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "课表 · 播放器 · 影视",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
            )
        }
    }
}
