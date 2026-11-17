package com.qzkt.timetable.ui.tools

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qzkt.timetable.data.tools.ExpressOverview
import com.qzkt.timetable.data.tools.ExpressSavedState
import com.qzkt.timetable.data.tools.ExpressStore
import com.qzkt.timetable.data.tools.ParcelDraft
import com.qzkt.timetable.data.tools.PddProfile
import com.qzkt.timetable.data.tools.ParcelListState
import com.qzkt.timetable.data.tools.ParcelStore
import com.qzkt.timetable.data.tools.SavedParcel
import com.qzkt.timetable.data.tools.capturedToDrafts
import com.qzkt.timetable.data.tools.parseParcelCapture
import com.qzkt.timetable.data.tools.validateParcelDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val CAPTURE_ASSET = "parcel/mdkd_package_capture.json"

/**
 * 个人中心页签。
 *
 * 小程序里的「个人中心」其实是拼多多账号页；这里是菜鸟包裹这个工具自己的账号与数据管理：
 * 拼多多登录态、手动运单（本机）、抓包快照导入、网页版入口。外观仍沿用页面的设计值。
 */
@Composable
fun PddAccountTab(
    store: ExpressStore,
    express: ExpressSavedState,
    overview: ExpressOverview?,
    profile: PddProfile? = null,
    onLogin: () -> Unit,
    onOpenPddPage: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parcelStore = remember { ParcelStore(context.applicationContext) }
    val parcelState by parcelStore.parcelState.collectAsStateWithLifecycle(initialValue = ParcelListState())
    var showAdd by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        // 账号卡
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PddSkin.card, RoundedCornerShape(8.dp))
                .border(1.dp, PddSkin.divider, RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 头像：接口给了就加载（Coil），没给就显示昵称首字，不编造
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(PddSkin.identityBg),
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = profile
                    if (shown != null && shown.hasAvatar) {
                        AsyncImage(
                            model = shown.avatarUrl,
                            contentDescription = "拼多多头像",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(shown?.displayName?.take(1) ?: "拼", fontSize = 18.sp, color = PddSkin.sub)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        profile?.displayName ?: "拼多多账号",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = PddSkin.text,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (express.hasCookie) "已登录（Cookie 保存在本机）" else "未登录：登录后才能读取取件点的包裹与身份码",
                        fontSize = 13.sp,
                        color = if (express.hasCookie) PddSkin.sub else PddSkin.red,
                    )
                    overview?.count?.let {
                        Spacer(Modifier.height(2.dp))
                        Text("当前包裹数：" + it, fontSize = 12.sp, color = PddSkin.muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PddButton(if (express.hasCookie) "重新登录" else "登录拼多多", filled = true) { onLogin() }
                PddButton("打开拼多多网页", filled = false) { onOpenPddPage() }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 手动运单（本机，不依赖登录）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PddSkin.card, RoundedCornerShape(8.dp))
                .border(1.dp, PddSkin.divider, RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本机运单", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = PddSkin.text)
                Spacer(Modifier.width(6.dp))
                Text("共 " + parcelState.parcels.size + " 条", fontSize = 12.sp, color = PddSkin.muted)
                Spacer(Modifier.weight(1f))
                Text("添加", fontSize = 14.sp, color = PddSkin.red, modifier = Modifier.clickable { showAdd = true })
            }
            Spacer(Modifier.height(6.dp))
            Text("手动录入的运单只保存在本机，不联网查询物流。", fontSize = 12.sp, color = PddSkin.muted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PddButton(if (importing) "导入中…" else "导入抓包快照", filled = false) {
                    if (importing) return@PddButton
                    scope.launch {
                        importing = true
                        try {
                            val raw = withContext(Dispatchers.IO) {
                                context.assets.open(CAPTURE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            }
                            val result = parcelStore.importCaptured(parseParcelCapture(raw))
                            notice = result.summary
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalArgumentException) {
                            error = e.message
                        } catch (e: IOException) {
                            error = "读取抓包快照失败：" + (e.message ?: "资源不可用")
                        } finally {
                            importing = false
                        }
                    }
                }
            }
            if (parcelState.parcels.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                parcelState.parcels.forEach { parcel ->
                    PddSavedParcelRow(
                        parcel = parcel,
                        onDelete = {
                            scope.launch {
                                runCatching { parcelStore.remove(parcel.id) }
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 其他
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PddSkin.card, RoundedCornerShape(8.dp))
                .border(1.dp, PddSkin.divider, RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            Text("关于", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = PddSkin.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "界面按拼多多「取快递」小程序 1:1 复刻（配色、文案、图标取自页面本身）。" +
                    "数据来自拼多多公开接口，需要你自己的登录态；仅保存在本机，仅用于个人查件。",
                fontSize = 12.sp,
                color = PddSkin.muted,
            )
            if (express.hasCookie) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "清除登录态",
                    fontSize = 14.sp,
                    color = PddSkin.red,
                    modifier = Modifier.clickable {
                        scope.launch {
                            store.update { it.copy(cookie = "", overviewJson = "", overviewAt = 0L, pickupJson = "", pickupAt = 0L) }
                            Toast.makeText(context, "已清除拼多多登录态", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }

        notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = PddSkin.link)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = PddSkin.red)
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showAdd) {
        AddParcelDialog(
            onDismiss = { showAdd = false },
            onSave = { draft ->
                scope.launch {
                    try {
                        parcelStore.add(draft)
                        showAdd = false
                        error = null
                    } catch (e: IllegalArgumentException) {
                        error = e.message
                    } catch (e: IOException) {
                        error = "保存失败：" + (e.message ?: "本地存储不可用")
                    }
                }
            },
        )
    }
}

@Composable
private fun PddButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val bg = if (filled) PddSkin.redSolid else PddSkin.card
    val fg = if (filled) PddSkin.onSolid else PddSkin.red
    Box(
        modifier = Modifier
            .height(34.dp)
            .background(bg, RoundedCornerShape(4.dp))
            .then(if (filled) Modifier else Modifier.border(1.dp, PddSkin.red, RoundedCornerShape(4.dp)))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, color = fg)
    }
}

@Composable
private fun PddSavedParcelRow(parcel: SavedParcel, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                parcel.name.ifBlank { "我的包裹" },
                fontSize = 14.sp,
                color = PddSkin.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (parcel.carrier.isNotBlank()) parcel.carrier + " · " else "") + parcel.trackingNumber,
                fontSize = 12.sp,
                color = PddSkin.muted,
            )
        }
        Text("删除", fontSize = 13.sp, color = PddSkin.red, modifier = Modifier.clickable { onDelete() })
    }
}

@Composable
private fun AddParcelDialog(onDismiss: () -> Unit, onSave: (ParcelDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var tracking by remember { mutableStateOf("") }
    var carrier by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加运单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("备注（可选）") }, singleLine = true)
                OutlinedTextField(
                    value = tracking,
                    onValueChange = { tracking = it.filterNot(Char::isWhitespace).take(50); localError = null },
                    label = { Text("运单号") },
                    singleLine = true,
                )
                OutlinedTextField(value = carrier, onValueChange = { carrier = it.take(40) }, label = { Text("快递公司（可选）") }, singleLine = true)
                localError?.let { Text(it, fontSize = 12.sp, color = PddSkin.red) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { validateParcelDraft(name, tracking, carrier) }
                    .onSuccess(onSave)
                    .onFailure { localError = it.message }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
