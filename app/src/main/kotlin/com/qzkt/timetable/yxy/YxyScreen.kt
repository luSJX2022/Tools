package com.qzkt.timetable.yxy

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YxyScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { YxySecureStore(context) }
    val electricityHistoryStore = remember(context) { ElectricityHistoryStore(context) }
    val api = remember { YxyClient() }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<YxySession?>(null) }
    var deviceId by remember { mutableStateOf("ym-" + UUID.randomUUID().toString().replace("-", "")) }
    var schoolCode by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var verificationCode by rememberSaveable { mutableStateOf("") }
    var captchaValue by rememberSaveable { mutableStateOf("") }
    var securityToken by remember { mutableStateOf("") }
    var captchaBase64 by remember { mutableStateOf("") }
    var captchaRequired by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<String?>(null) }
    var queryDate by rememberSaveable {
        mutableStateOf(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
    }
    var transactions by remember { mutableStateOf<List<YxyTransaction>>(emptyList()) }
    var electricityCampus by rememberSaveable { mutableStateOf("mgs") }
    var electricityRooms by remember { mutableStateOf<List<YxyElectricityRoom>>(emptyList()) }
    var selectedElectricityRoom by remember { mutableStateOf<YxyElectricityRoom?>(null) }
    var electricityMeters by remember { mutableStateOf<List<YxyElectricityMeter>>(emptyList()) }
    var selectedElectricityMeter by remember { mutableStateOf<YxyElectricityMeter?>(null) }
    var electricitySnapshots by remember { mutableStateOf<List<ElectricityBalanceSnapshot>>(emptyList()) }
    var meterLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var manualAreaId by rememberSaveable { mutableStateOf("") }
    var manualBuildingCode by rememberSaveable { mutableStateOf("") }
    var manualFloorCode by rememberSaveable { mutableStateOf("") }
    var manualRoomCode by rememberSaveable { mutableStateOf("") }
    var rechargeRecords by remember { mutableStateOf<List<YxyRechargeRecord>>(emptyList()) }
    var rechargePage by remember { mutableStateOf(1) }
    var rechargeHasMore by remember { mutableStateOf(false) }
    var usageRecords by remember { mutableStateOf<List<YxyUsageRecord>>(emptyList()) }
    var usagePage by remember { mutableStateOf(1) }
    var usageHasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        try {
            val saved = withContext(Dispatchers.IO) { store.load() }
            session = saved
            if (saved != null) {
                deviceId = saved.deviceId
                phone = saved.phone
                schoolCode = saved.schoolCode
            }
        } catch (e: GeneralSecurityException) {
            error = "无法解密本地登录状态，请清除应用数据后重新登录"
        } catch (e: JSONException) {
            error = "本地登录状态损坏，请重新登录"
        } catch (e: IllegalArgumentException) {
            error = e.message ?: "本地登录状态格式不正确"
        } finally {
            loading = false
        }
    }

    fun perform(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            status = null
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                error = e.message ?: "网络请求失败，请稍后重试"
            } catch (e: GeneralSecurityException) {
                error = e.message ?: "加密处理失败"
            } catch (e: JSONException) {
                error = e.message ?: "接口响应格式错误"
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "输入内容不正确"
            } catch (e: IllegalStateException) {
                error = e.message ?: "本地登录状态处理失败"
            } finally {
                busy = false
            }
        }
    }

    suspend fun refreshMeterHistory(room: YxyElectricityRoom, meters: List<YxyElectricityMeter>) {
        val history = withContext(Dispatchers.IO) {
            meters.associate { meter ->
                meter.mdtype to electricityHistoryStore.snapshots(room, meter.mdtype)
            }
        }
        electricitySnapshots = history.values.flatten()
        meterLabels = withContext(Dispatchers.IO) {
            meters.mapNotNull { meter ->
                electricityHistoryStore.label(room, meter.mdtype)?.let { meter.mdtype to it }
            }.toMap()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("易校园") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "仅用于查询你本人账号的校园卡。短信验证码由你手动触发；接口与学校代码可能因地区和版本不同而变化。",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (session == null) {
                OutlinedTextField(
                    value = schoolCode,
                    onValueChange = { schoolCode = it.filter(Char::isDigit).take(12) },
                    label = { Text("学校代码") },
                    supportingText = { Text("请输入你所在学校的代码；10337 仅为文档示例。") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(20) },
                    label = { Text("手机号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedButton(
                    enabled = !busy && schoolCode.isNotBlank() && phone.isNotBlank(),
                    onClick = {
                        perform {
                            val tokenData = withContext(Dispatchers.IO) {
                                api.getSecurityToken(schoolCode.trim(), deviceId)
                            }
                            securityToken = tokenData.optString("securityToken")
                            if (securityToken.isBlank()) throw YxyApiException("接口未返回安全令牌")
                            captchaRequired = tokenData.optInt("level") == 1
                            captchaBase64 = ""
                            captchaValue = ""
                            if (captchaRequired) {
                                captchaBase64 = withContext(Dispatchers.IO) {
                                    api.getCaptcha(schoolCode.trim(), deviceId, securityToken)
                                }
                                if (captchaBase64.isBlank()) throw YxyApiException("接口未返回图形验证码")
                            }
                            status = if (captchaRequired) {
                                "安全令牌已获取，请填写图形验证码后发送短信"
                            } else {
                                "安全令牌已获取，可以发送短信验证码"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (securityToken.isBlank()) "获取安全令牌 / 图形验证码" else "重新获取安全令牌")
                }
                if (captchaBase64.isNotBlank()) {
                    CaptchaImage(captchaBase64)
                    OutlinedTextField(
                        value = captchaValue,
                        onValueChange = { captchaValue = it.take(12) },
                        label = { Text("图形验证码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                Button(
                    enabled = !busy && securityToken.isNotBlank() &&
                        phone.isNotBlank() && (!captchaRequired || captchaValue.isNotBlank()),
                    onClick = {
                        perform {
                            withContext(Dispatchers.IO) {
                                api.sendVerificationCode(
                                    schoolCode.trim(),
                                    deviceId,
                                    phone.trim(),
                                    securityToken,
                                    captchaValue,
                                )
                            }
                            status = "短信验证码已发送。请遵守服务端频控，不要重复请求。"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("发送短信验证码")
                }
                OutlinedTextField(
                    value = verificationCode,
                    onValueChange = { verificationCode = it.filter(Char::isDigit).take(12) },
                    label = { Text("短信验证码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    enabled = !busy && schoolCode.isNotBlank() &&
                        phone.isNotBlank() && verificationCode.isNotBlank(),
                    onClick = {
                        perform {
                            val signedIn = withContext(Dispatchers.IO) {
                                api.login(schoolCode.trim(), deviceId, phone.trim(), verificationCode)
                            }
                            withContext(Dispatchers.IO) { store.save(signedIn) }
                            session = signedIn
                            verificationCode = ""
                            balance = withContext(Dispatchers.IO) {
                                api.getBalance(schoolCode.trim(), signedIn)
                            }
                            status = "登录成功"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录并查询余额")
                }
            } else {
                val activeSession = requireNotNull(session)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("校园卡余额", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            balance?.let { "¥ $it" } ?: "点击刷新余额",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "账号：${session?.phone.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    perform {
                                        balance = withContext(Dispatchers.IO) {
                                            api.getBalance(schoolCode.trim(), activeSession)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text("刷新余额")
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    perform {
                                        withContext(Dispatchers.IO) { store.clear() }
                                        session = null
                                        balance = null
                                        transactions = emptyList()
                                        electricityRooms = emptyList()
                                        selectedElectricityRoom = null
                                        rechargeRecords = emptyList()
                                        usageRecords = emptyList()
                                        electricityMeters = emptyList()
                                        selectedElectricityMeter = null
                                        electricitySnapshots = emptyList()
                                        securityToken = ""
                                        status = "已退出并清除本地加密会话"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("退出登录")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = queryDate,
                    onValueChange = { queryDate = it.filter(Char::isDigit).take(8) },
                    label = { Text("流水日期（YYYYMMDD）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    enabled = !busy && queryDate.length == 8,
                    onClick = {
                        perform {
                            transactions = withContext(Dispatchers.IO) {
                                api.getTransactions(schoolCode.trim(), activeSession, queryDate)
                            }
                            status = "查询到 ${transactions.size} 条消费记录"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("查询消费明细")
                }
                transactions.forEach { transaction ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(transaction.address.ifBlank { "消费记录" }, fontWeight = FontWeight.SemiBold)
                                Text(
                                    transaction.time,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("¥ ${transaction.money}", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("宿舍电费", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "选择电费系统并读取已绑定宿舍，可查询缴费记录和每日用电明细。若尚未绑定，可先前往易校园官方授权页完成绑定。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = electricityCampus == "mgs",
                                    onClick = {
                                        electricityCampus = "mgs"
                                        electricityRooms = emptyList()
                                        selectedElectricityRoom = null
                                        rechargeRecords = emptyList()
                                        usageRecords = emptyList()
                                        electricityMeters = emptyList()
                                        selectedElectricityMeter = null
                                        electricitySnapshots = emptyList()
                                    },
                                    label = { Text("MGS") },
                                )
                                FilterChip(
                                    selected = electricityCampus == "zhpf",
                                    onClick = {
                                        electricityCampus = "zhpf"
                                        electricityRooms = emptyList()
                                        selectedElectricityRoom = null
                                        rechargeRecords = emptyList()
                                        usageRecords = emptyList()
                                    },
                                    label = { Text("ZHPF") },
                                )
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    perform {
                                        val rooms = withContext(Dispatchers.IO) {
                                            api.getElectricityRooms(
                                                activeSession.schoolCode,
                                                activeSession,
                                                electricityCampus,
                                            )
                                        }
                                        electricityRooms = rooms
                                        selectedElectricityRoom = rooms.firstOrNull()
                                        electricityMeters = emptyList()
                                        selectedElectricityMeter = null
                                        electricitySnapshots = emptyList()
                                        rechargeRecords = emptyList()
                                        rechargePage = 1
                                        rechargeHasMore = false
                                        usageRecords = emptyList()
                                        usagePage = 1
                                        usageHasMore = false
                                        status = if (rooms.isEmpty()) {
                                            "没有找到已绑定的宿舍电费房间；请确认电费系统选择正确，并在易校园中先绑定房间"
                                        } else {
                                            "找到 ${rooms.size} 个已绑定房间"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("读取已绑定宿舍")
                            }
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    try {
                                        val url = api.schoolDormPaymentUrl()
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                        status = "已打开学校宿舍缴费页；完成房间绑定后返回此处，再读取宿舍"
                                    } catch (e: ActivityNotFoundException) {
                                        error = "无法打开易校园授权页，请安装或选择一个浏览器后重试"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("前往易校园绑定宿舍")
                            }
                            Text("手动填写房间 API ID", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "如果未能读取已绑定房间，可从学校电费页面提供的房间信息填写。此处不会猜测或内置楼栋房号。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = manualAreaId,
                                onValueChange = { manualAreaId = it.take(64) },
                                label = { Text("区域 ID (areaId)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = manualBuildingCode,
                                onValueChange = { manualBuildingCode = it.take(64) },
                                label = { Text("楼栋 ID (buildingCode)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = manualFloorCode,
                                onValueChange = { manualFloorCode = it.take(64) },
                                label = { Text("楼层 ID (floorCode)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = manualRoomCode,
                                onValueChange = { manualRoomCode = it.take(64) },
                                label = { Text("房间 ID (roomCode)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    try {
                                        val room = createManualElectricityRoom(
                                            manualAreaId,
                                            manualBuildingCode,
                                            manualFloorCode,
                                            manualRoomCode,
                                            "",
                                        )
                                        selectedElectricityRoom = room
                                        electricityMeters = emptyList()
                                        selectedElectricityMeter = null
                                        electricitySnapshots = emptyList()
                                        rechargeRecords = emptyList()
                                        usageRecords = emptyList()
                                        status = "已选择手动填写的房间 ${room.displayName}"
                                    } catch (e: IllegalArgumentException) {
                                        error = e.message ?: "房间 ID 不正确"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("使用此房间")
                            }
                            electricityRooms.forEach { room ->
                                FilterChip(
                                    selected = selectedElectricityRoom == room,
                                    onClick = {
                                        selectedElectricityRoom = room
                                        rechargeRecords = emptyList()
                                        rechargePage = 1
                                        rechargeHasMore = false
                                        usageRecords = emptyList()
                                        usagePage = 1
                                        usageHasMore = false
                                        electricityMeters = emptyList()
                                        selectedElectricityMeter = null
                                        electricitySnapshots = emptyList()
                                    },
                                    label = { Text(room.displayName) },
                                )
                            }
                            if (selectedElectricityRoom != null) {
                                Text(
                                    "当前房间：${requireNotNull(selectedElectricityRoom).displayName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        perform {
                                            val room = requireNotNull(selectedElectricityRoom)
                                            val result = withContext(Dispatchers.IO) {
                                                api.getElectricityMeters(
                                                    activeSession.schoolCode,
                                                    activeSession,
                                                    electricityCampus,
                                                    room,
                                                )
                                            }
                                            withContext(Dispatchers.IO) {
                                                result.forEach { electricityHistoryStore.record(room, it) }
                                            }
                                            electricityMeters = result
                                            selectedElectricityMeter = result.firstOrNull()
                                            usageRecords = emptyList()
                                            usagePage = 1
                                            usageHasMore = false
                                            refreshMeterHistory(room, result)
                                            status = "读取到 ${result.size} 个表计；余额以电费系统实际返回数据为准"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("查询表计余额")
                                }
                                electricityMeters.forEach { meter ->
                                    ElectricityMeterCard(
                                        meter = meter,
                                        label = meterLabels[meter.mdtype],
                                        history = electricitySnapshots.filter { it.mdtype == meter.mdtype },
                                        estimate = if (selectedElectricityMeter?.mdtype == meter.mdtype) {
                                            estimateElectricityDays(meter.remainingKwh, usageRecords)
                                        } else {
                                            null
                                        },
                                        selected = selectedElectricityMeter?.mdtype == meter.mdtype,
                                        onSelect = {
                                            selectedElectricityMeter = meter
                                            usageRecords = emptyList()
                                            usagePage = 1
                                            usageHasMore = false
                                        },
                                        onLabelChange = { label ->
                                            perform {
                                                withContext(Dispatchers.IO) {
                                                    electricityHistoryStore.setLabel(
                                                        requireNotNull(selectedElectricityRoom),
                                                        meter.mdtype,
                                                        label,
                                                    )
                                                }
                                                meterLabels = meterLabels.toMutableMap().apply {
                                                    if (label == null) remove(meter.mdtype)
                                                    else put(meter.mdtype, label)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            if (selectedElectricityRoom != null) {
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        perform {
                                            val result = withContext(Dispatchers.IO) {
                                                api.getElectricityRechargeRecords(
                                                    activeSession.schoolCode,
                                                    activeSession,
                                                    electricityCampus,
                                                    requireNotNull(selectedElectricityRoom),
                                                    1,
                                                )
                                            }
                                            rechargeRecords = result.records
                                            rechargePage = 1
                                            rechargeHasMore = result.hasMore
                                            status = "第 1 页，${result.records.size} 条缴费记录"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("查询缴费记录")
                                }
                            }
                            rechargeRecords.forEach { record ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        record.datetime.ifBlank { "缴费记录" },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(record.money, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (rechargeRecords.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        enabled = !busy && rechargePage > 1,
                                        onClick = {
                                            perform {
                                                val previousPage = rechargePage - 1
                                                val result = withContext(Dispatchers.IO) {
                                                    api.getElectricityRechargeRecords(
                                                        activeSession.schoolCode,
                                                        activeSession,
                                                        electricityCampus,
                                                        requireNotNull(selectedElectricityRoom),
                                                        previousPage,
                                                    )
                                                }
                                                rechargeRecords = result.records
                                                rechargePage = previousPage
                                                rechargeHasMore = result.hasMore
                                            }
                                        },
                                    ) {
                                        Text("上一页")
                                    }
                                    Text("第 $rechargePage 页", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(
                                        enabled = !busy && rechargeHasMore,
                                        onClick = {
                                            perform {
                                                val nextPage = rechargePage + 1
                                                val result = withContext(Dispatchers.IO) {
                                                    api.getElectricityRechargeRecords(
                                                        activeSession.schoolCode,
                                                        activeSession,
                                                        electricityCampus,
                                                        requireNotNull(selectedElectricityRoom),
                                                        nextPage,
                                                    )
                                                }
                                                rechargeRecords = result.records
                                                rechargePage = nextPage
                                                rechargeHasMore = result.hasMore
                                            }
                                        },
                                    ) {
                                        Text("下一页")
                                    }
                                }
                            }
                            Text("用电明细", style = MaterialTheme.typography.titleSmall)
                            selectedElectricityMeter?.let {
                                Text(
                                    "当前表计：${meterLabels[it.mdtype] ?: it.apiName.ifBlank { "类型 ${it.mdtype}" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selectedElectricityRoom != null) {
                                    Button(
                                        enabled = !busy &&
                                            (electricityCampus != "zhpf" || selectedElectricityMeter != null),
                                        onClick = {
                                            perform {
                                                val result = withContext(Dispatchers.IO) {
                                                    api.getElectricityUsageRecords(
                                                        activeSession.schoolCode,
                                                        activeSession,
                                                        electricityCampus,
                                                        requireNotNull(selectedElectricityRoom),
                                                        1,
                                                        selectedElectricityMeter?.mdtype,
                                                    )
                                                }
                                                usageRecords = result.records
                                                usagePage = 1
                                                usageHasMore = result.hasMore
                                                status = "第 1 页，${result.records.size} 条用电明细"
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("查询用电明细")
                                    }
                                }
                                usageRecords.forEach { record ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            record.datetime.ifBlank { "用电记录" },
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            record.usage,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                if (usageRecords.isNotEmpty() && electricityCampus == "mgs") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedButton(
                                            enabled = !busy && usagePage > 1,
                                            onClick = {
                                                perform {
                                                    val previousPage = usagePage - 1
                                                    val result = withContext(Dispatchers.IO) {
                                                        api.getElectricityUsageRecords(
                                                            activeSession.schoolCode,
                                                            activeSession,
                                                            electricityCampus,
                                                            requireNotNull(selectedElectricityRoom),
                                                            previousPage,
                                                            selectedElectricityMeter?.mdtype,
                                                        )
                                                    }
                                                    usageRecords = result.records
                                                    usagePage = previousPage
                                                    usageHasMore = result.hasMore
                                                }
                                            },
                                        ) {
                                            Text("上一页")
                                        }
                                        Text("第 $usagePage 页", style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(
                                            enabled = !busy && usageHasMore,
                                            onClick = {
                                                perform {
                                                    val nextPage = usagePage + 1
                                                    val result = withContext(Dispatchers.IO) {
                                                        api.getElectricityUsageRecords(
                                                            activeSession.schoolCode,
                                                            activeSession,
                                                            electricityCampus,
                                                            requireNotNull(selectedElectricityRoom),
                                                            nextPage,
                                                            selectedElectricityMeter?.mdtype,
                                                        )
                                                    }
                                                    usageRecords = result.records
                                                    usagePage = nextPage
                                                    usageHasMore = result.hasMore
                                                }
                                            },
                                        ) {
                                            Text("下一页")
                                        }
                                    }
                            }
                        }
                }
            }

            if (busy) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            status?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ElectricityMeterCard(
    meter: YxyElectricityMeter,
    label: String?,
    history: List<ElectricityBalanceSnapshot>,
    estimate: ElectricityEstimate?,
    selected: Boolean,
    onSelect: () -> Unit,
    onLabelChange: (String?) -> Unit,
) {
    var menuExpanded by remember(meter.mdtype) { mutableStateOf(false) }
    val inferredLabel = when {
        meter.apiName.contains("照明") -> "照明"
        meter.apiName.contains("空调") -> "空调"
        else -> null
    }
    val title = label ?: inferredLabel ?: meter.apiName.ifBlank { "表计 ${meter.mdtype}" }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("接口类型：${meter.mdtype}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { menuExpanded = true }) { Text("设置用途") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    listOf("照明", "空调", "其他").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                menuExpanded = false
                                onLabelChange(option)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("恢复接口名称") },
                        onClick = {
                            menuExpanded = false
                            onLabelChange(null)
                        },
                    )
                }
            }
            if (meter.remainingKwh != null) {
                Text(
                    "剩余电量：${String.format(java.util.Locale.ROOT, "%.2f", meter.remainingKwh)} kWh",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    "接口未返回此表计的独立剩余电量，不显示余额或据此估算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected && meter.remainingKwh != null) {
                when {
                    estimate == null -> Text(
                        "剩余天数：需余额和最近 7 个完整自然日的每日用量数据",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    estimate.remainingDays == null -> Text(
                        "近 7 日平均用量：${String.format(java.util.Locale.ROOT, "%.2f", estimate.averageDailyKwh)} kWh/日；近期用量为零，暂无法估算剩余天数",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        "近 7 日平均用量：${String.format(java.util.Locale.ROOT, "%.2f", estimate.averageDailyKwh)} kWh/日；预计剩余 ${estimate.remainingDays} 天",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text("余额趋势", style = MaterialTheme.typography.labelLarge)
            if (history.isEmpty()) {
                Text("尚无余额快照", style = MaterialTheme.typography.bodySmall)
            } else {
                ElectricityTrendChart(history)
                Text(
                    "根据本机成功查询后保存的余额生成（${history.size} 个快照）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ElectricityTrendChart(history: List<ElectricityBalanceSnapshot>) {
    val points = history.takeLast(30).map(ElectricityBalanceSnapshot::balanceKwh)
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        if (points.isEmpty()) return@Canvas
        val minValue = points.minOrNull() ?: return@Canvas
        val maxValue = points.maxOrNull() ?: return@Canvas
        val valueRange = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
        val plotted = points.mapIndexed { index, value ->
            Offset(
                x = if (points.size == 1) size.width / 2 else size.width * index / (points.size - 1),
                y = size.height - (((value - minValue) / valueRange).toFloat() * size.height),
            )
        }
        if (plotted.size == 1) {
            drawCircle(lineColor, radius = 5.dp.toPx(), center = plotted.first())
        } else {
            plotted.zipWithNext().forEach { (start, end) ->
                drawLine(lineColor, start, end, strokeWidth = 3.dp.toPx())
            }
            plotted.forEach { drawCircle(lineColor, radius = 3.dp.toPx(), center = it) }
        }
    }
}

@Composable
private fun CaptchaImage(base64: String) {
    val bitmap = remember(base64) {
        try {
            val encoded = base64.substringAfter(',', base64)
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = "图形验证码", modifier = Modifier.size(width = 180.dp, height = 64.dp))
    } else {
        Text("图形验证码图片无法显示，请重新获取", color = MaterialTheme.colorScheme.error)
    }
}
