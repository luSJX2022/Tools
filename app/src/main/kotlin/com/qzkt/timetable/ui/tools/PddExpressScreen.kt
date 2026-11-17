package com.qzkt.timetable.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.qzkt.timetable.R
import com.qzkt.timetable.data.tools.ExpressOverview
import com.qzkt.timetable.data.tools.ExpressPackage
import com.qzkt.timetable.data.tools.ExpressSavedState
import com.qzkt.timetable.data.tools.ExpressStore
import com.qzkt.timetable.data.tools.PddApiException
import com.qzkt.timetable.data.tools.PddAuth
import com.qzkt.timetable.data.PluginLog
import com.qzkt.timetable.data.tools.PddExpressApi
import com.qzkt.timetable.data.tools.PddProfile
import com.qzkt.timetable.data.tools.PickupCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------------- 拼多多「取快递」页面的设计值（取自页面 CSS，1rem = 16dp） ---------------- */

/**
 * 页面配色（按角色定义）。浅色就是页面 CSS 的原始值；暗色把同一套角色换成深底浅字，
 * 品牌红只用于实心按钮，文字/边框用的红则提亮以保证深色下的对比度。
 */
@Immutable
internal data class PddColors(
    val bg: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val muted: Color,
    val red: Color,
    val redSolid: Color,
    val tabActive: Color,
    val link: Color,
    val identityBg: Color,
    val divider: Color,
    val tip: Color,
    val onSolid: Color,
)

internal val PddLight = PddColors(
    bg = Color.White,
    card = Color.White,
    text = Color(0xFF151516),
    sub = Color(0xFF58595B),
    muted = Color(0xFF9C9C9C),
    red = Color(0xFFE02E24),
    redSolid = Color(0xFFE02E24),
    tabActive = Color(0xFFE53B43),
    link = Color(0xFF0076FF),
    identityBg = Color(0xFFF5F5F5),
    divider = Color(0x14000000),
    tip = Color(0x99000000),
    onSolid = Color.White,
)

internal val PddDark = PddColors(
    bg = Color(0xFF121212),
    card = Color(0xFF1E1E1E),
    text = Color(0xFFEDEDED),
    sub = Color(0xFFB8B8B8),
    muted = Color(0xFF8A8A8A),
    red = Color(0xFFFF6B5E),
    redSolid = Color(0xFFE02E24),
    tabActive = Color(0xFFFF6B5E),
    link = Color(0xFF7FB2FF),
    identityBg = Color(0xFF0E0E0E),
    divider = Color(0x33FFFFFF),
    tip = Color(0xCCFFFFFF),
    onSolid = Color.White,
)

/** 纯函数，便于单测：给定是否深色，返回对应配色。 */
internal fun pddColors(isDark: Boolean): PddColors = if (isDark) PddDark else PddLight

/**
 * 页面颜色。跟随 App 的主题设置（QzktTheme）：用 MaterialTheme 的 surface 亮度判断深浅，
 * 所以在设置里切「浅色 / 深色 / 跟随系统」都会即时生效，不用每个界面自己传参数。
 */
internal object PddSkin {
    @Composable
    private fun colors(): PddColors = pddColors(MaterialTheme.colorScheme.surface.luminance() < 0.5f)

    val bg: Color @Composable get() = colors().bg
    val card: Color @Composable get() = colors().card
    val text: Color @Composable get() = colors().text
    val sub: Color @Composable get() = colors().sub
    val muted: Color @Composable get() = colors().muted
    val red: Color @Composable get() = colors().red
    val redSolid: Color @Composable get() = colors().redSolid
    val tabActive: Color @Composable get() = colors().tabActive
    val link: Color @Composable get() = colors().link
    val identityBg: Color @Composable get() = colors().identityBg
    val divider: Color @Composable get() = colors().divider
    val tip: Color @Composable get() = colors().tip
    val onSolid: Color @Composable get() = colors().onSolid
}

private val TABS = listOf(
    Triple("取快递", R.drawable.pdd_tab_package, 0),
    Triple("身份码", R.drawable.pdd_tab_scan, 1),
    Triple("个人中心", R.drawable.pdd_tab_person, 2),
)

/**
 * 拼多多「取快递」小程序的 1:1 原生复刻。
 *
 * 设计值全部取自页面真实 CSS（1rem=16dp）：主文字 #151516、次文字 #58595B、弱化 #9C9C9C、
 * 拼多多红 #E02E24、选中态 #E53B43、链接 #0076FF、身份码页底色 #F5F5F5；
 * 图标与 banner logo 也是从页面里抠出来的原始资源。文案同样用页面原文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PddExpressScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { ExpressStore(context) }
    val express by store.state.collectAsStateWithLifecycle(initialValue = ExpressSavedState())
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<ExpressOverview?>(null) }
    var code by remember { mutableStateOf<PickupCode?>(null) }
    var profile by remember { mutableStateOf<PddProfile?>(null) }
    var busyPackages by remember { mutableStateOf(false) }
    var busyCode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var showPddPage by remember { mutableStateOf(false) }
    var showLogisticsHint by remember { mutableStateOf(false) }

    // 缓存里的原文解析成界面模型
    LaunchedEffect(express.overviewJson, express.overviewAt) {
        overview = express.overviewJson.takeIf { it.isNotBlank() }?.let {
            runCatching { PddExpressApi.parseExpressOverview(it, express.overviewAt) }.getOrNull()
        }
    }
    LaunchedEffect(express.pickupJson, express.pickupAt) {
        code = express.pickupJson.takeIf { it.isNotBlank() }?.let {
            runCatching { PddExpressApi.parsePickupCode(it, express.pickupAt) }.getOrNull()
        }
    }
    LaunchedEffect(express.profileJson, express.profileAt) {
        profile = express.profileJson.takeIf { it.isNotBlank() }?.let {
            runCatching { PddExpressApi.parseProfile(it, express.profileAt) }.getOrNull()
        }
    }

    fun refreshPackages() {
        if (busyPackages) return
        val auth = PddAuth(express.cookie)
        scope.launch {
            busyPackages = true; error = null
            try {
                val raw = withContext(Dispatchers.IO) { PddExpressApi().fetchPackagesRaw(auth) }
                PddExpressApi.parseExpressOverview(raw, System.currentTimeMillis())
                store.update { it.copy(overviewJson = raw, overviewAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PddApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "刷新失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                busyPackages = false
            }
        }
    }

    fun refreshCode() {
        if (busyCode) return
        val auth = PddAuth(express.cookie)
        scope.launch {
            busyCode = true; error = null
            try {
                val raw = withContext(Dispatchers.IO) { PddExpressApi().generatePickupCodeRaw(auth) }
                PddExpressApi.parsePickupCode(raw, System.currentTimeMillis())
                store.update { it.copy(pickupJson = raw, pickupAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PddApiException) {
                error = e.message
            } catch (e: Exception) {
                error = "身份码生成失败：" + (e.message ?: e.javaClass.simpleName)
            } finally {
                busyCode = false
            }
        }
    }

    fun refreshProfile() {
        val auth = PddAuth(express.cookie)
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { PddExpressApi().fetchProfileRaw(auth) }
                PddExpressApi.parseProfile(raw, System.currentTimeMillis())
                store.update { it.copy(profileJson = raw, profileAt = System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 头像昵称拿不到不影响包裹和身份码，静默失败
            }
        }
    }

    // 有登录态时进页面就自动拉一次，和 H5 行为一致
    LaunchedEffect(express.hasCookie) {
        if (express.hasCookie) {
            refreshPackages()
            refreshCode()
            refreshProfile()
        }
    }

    // 小程序的「去查看」是拉起拼多多 App；网页版在 App 内会被限制（一直加载中），先给出说明
    if (showLogisticsHint) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogisticsHint = false },
            title = { Text("查看实时物流") },
            text = {
                Text(
                    "实时物流信息请在拼多多 App【个人中心-我的订单】查看。\n\n" +
                        "应用内的拼多多网页版会被官方限制（可能一直显示「加载中」），建议直接用这里的原生界面查包裹和身份码。",
                    fontSize = 14.sp,
                    color = PddSkin.sub,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showLogisticsHint = false }) { Text("知道了") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showLogisticsHint = false
                    showPddPage = true
                }) { Text("仍要打开网页版") }
            },
        )
    }

    if (showLogin) {
        ParcelLoginScreen(
            onBack = { showLogin = false },
            onLoggedIn = { cookie ->
                scope.launch {
                    store.update { it.copy(cookie = cookie) }
                    PluginLog.i("菜鸟包裹", "拼多多登录态已保存（" + cookie.length + " 字符）")
                    showLogin = false
                }
            },
        )
        return
    }
    if (showPddPage) {
        ParcelPddPageScreen(onBack = { showPddPage = false })
        return
    }

    Scaffold(
        containerColor = PddSkin.bg,
        topBar = {
            TopAppBar(
                title = { Text("取快递", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = PddSkin.text) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = PddSkin.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PddSkin.bg),
            )
        },
        bottomBar = {
            PddTabBar(selected = tab, onSelect = { tab = it })
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> TakeExpressTab(
                    express = express,
                    overview = overview,
                    busy = busyPackages,
                    error = error,
                    onRefresh = { refreshPackages() },
                    onLogin = { showLogin = true },
                    onShowLogisticsHint = { showLogisticsHint = true },
                    onGotoAccount = { tab = 2 },
                )
                1 -> IdentityCodeTab(
                    express = express,
                    code = code,
                    busy = busyCode,
                    error = error,
                    onRefresh = { refreshCode() },
                    onLogin = { showLogin = true },
                )
                else -> PddAccountTab(
                    store = store,
                    express = express,
                    overview = overview,
                    profile = profile,
                    onLogin = { showLogin = true },
                    onOpenPddPage = { showPddPage = true },
                )
            }
        }
    }
}

/** 底部三个页签：图标 + 文案，尺寸/颜色照页面 CSS。 */
@Composable
private fun PddTabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(PddSkin.bg)) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PddSkin.divider))
        // 页面 CSS：图标距顶 11px + 图标 24px + 文案 13px + 距底 8px ≈ 60dp
        Row(modifier = Modifier.fillMaxWidth().height(60.dp)) {
            TABS.forEach { (label, iconRes, index) ->
                val active = selected == index
                val tint = if (active) PddSkin.tabActive else PddSkin.sub
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize().clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = label,
                        modifier = Modifier.size(24.dp),
                        colorFilter = ColorFilter.tint(tint),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(label, fontSize = 13.sp, color = tint, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
    }
}

/* ---------------- 页签 1：取快递 ---------------- */

@Composable
private fun TakeExpressTab(
    express: ExpressSavedState,
    overview: ExpressOverview?,
    busy: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onShowLogisticsHint: () -> Unit,
    onGotoAccount: () -> Unit,
) {
    var showRaw by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // 顶部 banner：logo + 两行文案 +「去查看」
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.pdd_banner_logo),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    overview?.bannerText?.takeIf { it.isNotBlank() } ?: "你有0个包裹正在运输中",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = PddSkin.text,
                )
                Spacer(Modifier.height(2.dp))
                Text("打开拼多多app【个人中心-我的订单】，查看实时物流信息", fontSize = 12.sp, color = PddSkin.muted)
            }
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .background(PddSkin.redSolid, RoundedCornerShape(4.dp))
                    .clickable { onShowLogisticsHint() }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("去查看", fontSize = 14.sp, color = Color.White)
            }
        }

        // 下拉刷新提示 + 刷新按钮（H5 用下拉手势，这里给一个等效的点击刷新）
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onRefresh() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (busy) "正在刷新…" else "下拉可以刷新（点这里也可以）",
                fontSize = 12.sp,
                color = PddSkin.muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        // 站点名（页面顶部会显示当前取件点）
        overview?.stationName?.takeIf { it.isNotBlank() }?.let { station ->
            Text(
                "当前站点：" + station,
                fontSize = 13.sp,
                color = PddSkin.sub,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        // 数据不对时能自查：把接口原始响应摊开看
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Text(
                "查看接口原始数据",
                fontSize = 12.sp,
                color = PddSkin.link,
                modifier = Modifier.clickable { showRaw = true },
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (overview == null) "还没拿到数据" else "包裹 " + overview!!.packages.size + " 个",
                fontSize = 12.sp,
                color = PddSkin.muted,
            )
        }

        if (showRaw) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRaw = false },
                title = { Text("接口原始数据") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            express.overviewJson.ifBlank { "还没有响应：先点「下拉可以刷新」或在个人中心里登录拼多多。" },
                            fontSize = 10.sp,
                            color = PddSkin.sub,
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showRaw = false }) { Text("关闭") }
                },
            )
        }

        if (!express.hasCookie) {
            PddEmptyState(
                title = "登录后查看你在取件点的包裹",
                tip = "用拼多多账号登录即可，不需要微信",
                primary = "用拼多多账号登录",
                onPrimary = onLogin,
                secondary = null,
                onSecondary = null,
            )
            return@Column
        }

        error?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = PddSkin.red,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        val packages = overview?.packages.orEmpty()
        if (packages.isEmpty()) {
            val tails = overview?.mobileTails.orEmpty()
            PddEmptyState(
                title = if (tails.isEmpty()) "当前站点暂无收到的快递" else "当前站点暂无手机尾号" + tails.joinToString("、") + "收到的快递",
                tip = "您可尝试以下方式查询未展示快递",
                primary = "绑定手机号",
                onPrimary = onLogin,
                secondary = "添加快递",
                onSecondary = onGotoAccount,
            )
        } else {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                packages.forEach { pkg -> PddPackageCard(pkg) }
            }
            Spacer(Modifier.height(12.dp))
        }
        PreviewGallery(
            desc = overview?.listPreviewDesc?.takeIf { it.isNotBlank() } ?: "查看我收到的快递",
            images = overview?.listPreviewImages.orEmpty(),
        )
        // 已取出的包裹只出现在这里（接口的 package_out_list），不会混进待取件
        RecentlyPickedSection(recent = overview?.recentPackages.orEmpty())

        // 意见反馈（页面里是右上角黑色小胶囊，这里放在列表下方更顺手）
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp, bottom = 12.dp)
                    .height(17.dp)
                    .width(54.dp)
                    .background(Color(0x4D000000), RoundedCornerShape(topStart = 100.dp, bottomStart = 100.dp))
                    .clickable { onGotoAccount() },
                contentAlignment = Alignment.Center,
            ) {
                Text("意见反馈", fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

/** 「查看我收到的快递」图片区：页面里用三张图 + 文案做引导，接口 list_preview 给的就是这些。 */
@Composable
private fun PreviewGallery(desc: String, images: List<String>) {
    if (images.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(desc, fontSize = 14.sp, color = PddSkin.text)
            Spacer(Modifier.width(4.dp))
            Text("›", fontSize = 16.sp, color = PddSkin.muted)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            images.take(3).forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp).background(PddSkin.identityBg, RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PddEmptyState(
    title: String,
    tip: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String?,
    onSecondary: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 15.sp, color = PddSkin.sub, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(tip, fontSize = 14.sp, color = PddSkin.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(39.dp)
                    .border(1.dp, PddSkin.red, RoundedCornerShape(4.dp))
                    .clickable { onPrimary() },
                contentAlignment = Alignment.Center,
            ) {
                Text(primary, fontSize = 16.sp, color = PddSkin.red)
            }
            if (secondary != null && onSecondary != null) {
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .height(39.dp)
                        .background(PddSkin.redSolid, RoundedCornerShape(4.dp))
                        .clickable { onSecondary() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(secondary, fontSize = 16.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * 近一周已取出快递（页面里对应 package_out_list）：
 * 首页显示最多 3 张包裹图 + 件数，点开是完整列表，每条明确标「已取出」。
 */
@Composable
private fun RecentlyPickedSection(recent: List<ExpressPackage>) {
    var showAll by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("近一周已取出快递", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PddSkin.text)
            Spacer(Modifier.weight(1f))
            if (recent.isNotEmpty()) {
                Text(
                    "共 " + recent.size + " 件 ›",
                    fontSize = 12.sp,
                    color = PddSkin.link,
                    modifier = Modifier.clickable { showAll = true },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (recent.isEmpty()) {
            Text("暂无", fontSize = 13.sp, color = PddSkin.muted)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.take(3).forEach { pkg ->
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .background(PddSkin.identityBg, RoundedCornerShape(6.dp))
                            .clickable { showAll = true },
                    ) {
                        if (pkg.picUrl.isNotBlank()) {
                            AsyncImage(
                                model = pkg.picUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(Color(0x66000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text("已取出", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAll = false },
            title = { Text("近一周共取出 " + recent.size + " 个快递") },
            text = {
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    recent.forEach { pkg ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (pkg.carrier.ifBlank { "包裹" }) + " " + pkg.trackingNumber,
                                    fontSize = 14.sp,
                                    color = PddSkin.text,
                                )
                                val extra = (if (pkg.station.isNotBlank()) pkg.station else "") +
                                    (if (pkg.operateTime.isNotBlank()) (if (pkg.station.isNotBlank()) " · " else "") + pkg.operateTime else "")
                                if (extra.isNotBlank()) Text(extra, fontSize = 12.sp, color = PddSkin.muted)
                            }
                            Text("已取出", fontSize = 12.sp, color = PddSkin.muted)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showAll = false }) { Text("关闭") }
            },
        )
    }
}

/**
 * 一个包裹卡片，按页面 .package-code / .package-shipping-name 的样式：
 * 取件码 19sp Semibold（带「取件码」小标签，点一下复制），下面一行 14sp #9C9C9C 的「快递公司 + 运单号」。
 */
@Composable
private fun PddPackageCard(pkg: ExpressPackage) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(PddSkin.card, RoundedCornerShape(8.dp))
            .border(1.dp, PddSkin.divider, RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                if (pkg.pickCode.isNotBlank()) {
                    Text("取件码", fontSize = 14.sp, color = PddSkin.sub)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        pkg.pickCode,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PddSkin.text,
                        modifier = Modifier.clickable {
                            val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            manager.setPrimaryClip(android.content.ClipData.newPlainText("pickCode", pkg.pickCode))
                            android.widget.Toast.makeText(context, "取件码已复制", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                } else {
                    Text("取件码待同步", fontSize = 14.sp, color = PddSkin.muted)
                }
            }
            Text("待取件", fontSize = 12.sp, color = PddSkin.red)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            (pkg.carrier.ifBlank { "包裹" }) + " " + pkg.trackingNumber,
            fontSize = 14.sp,
            color = PddSkin.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (pkg.station.isNotBlank() || pkg.operateTime.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            val extra = (if (pkg.station.isNotBlank()) pkg.station else "") +
                (if (pkg.station.isNotBlank() && pkg.operateTime.isNotBlank()) " · " else "") +
                (if (pkg.operateTime.isNotBlank()) "到站 " + pkg.operateTime else "")
            Text(extra, fontSize = 12.sp, color = PddSkin.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ---------------- 页签 2：身份码 ---------------- */

@Composable
private fun IdentityCodeTab(
    express: ExpressSavedState,
    code: PickupCode?,
    busy: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
) {
    var qrMode by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(code?.code, code?.fetchedAt) {
        val current = code ?: return@LaunchedEffect
        now = System.currentTimeMillis()
        while (current.remainingSeconds(now) > 0) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PddSkin.identityBg)
            .padding(start = 7.dp, end = 7.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("身份码每10分钟自动更新", fontSize = 16.sp, color = PddSkin.tip)
            Spacer(Modifier.width(5.dp))
            Text(
                if (qrMode) "切换灯条身份码" else "切换出库身份码",
                fontSize = 16.sp,
                color = PddSkin.link,
                modifier = Modifier.clickable { qrMode = !qrMode },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(PddSkin.card, RoundedCornerShape(4.dp)),
        ) {
            when {
                !express.hasCookie -> PddEmptyState(
                    title = "登录后查看身份码",
                    tip = "用拼多多账号登录即可，不需要微信",
                    primary = "用拼多多账号登录",
                    onPrimary = onLogin,
                    secondary = null,
                    onSecondary = null,
                )
                code == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("身份码生成中", fontSize = 15.sp, color = PddSkin.sub)
                    Spacer(Modifier.height(12.dp))
                    Text("点这里生成", fontSize = 14.sp, color = PddSkin.link, modifier = Modifier.clickable { onRefresh() })
                    error?.let { Spacer(Modifier.height(10.dp)); Text(it, fontSize = 12.sp, color = PddSkin.red, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                else -> IdentityCodeContent(
                    code = code,
                    qrMode = qrMode,
                    now = now,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

/**
 * 身份码卡片内容。H5 里这块是 rotate(90deg) 的：白卡片竖着放，条形码横向铺满整张卡片的高度，
 * 所以这里也把内容旋转 90°，保证和微信里看到的一样、店员能扫。
 */
@Composable
private fun IdentityCodeContent(
    code: PickupCode,
    qrMode: Boolean,
    now: Long,
    onRefresh: () -> Unit,
) {
    val remaining = code.remainingSeconds(now)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cardWidth = maxWidth
        val cardHeight = maxHeight
        if (qrMode) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val qr = remember(code.code) { runCatching { qrBitmap(code.code, 512) }.getOrNull() }
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "身份码二维码",
                        modifier = Modifier.size(220.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(code.code, fontSize = 18.sp, color = PddSkin.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("为避免快递丢失，请勿随意分享身份码", fontSize = 14.sp, color = PddSkin.muted)
                Spacer(Modifier.height(10.dp))
                IdentityFooter(code = code, remaining = remaining, onRefresh = onRefresh)
            }
        } else {
            // 旋转 90°：宽高对调后铺满白色卡片
            Column(
                modifier = Modifier
                    .width(cardHeight)
                    .height(cardWidth)
                    .rotate(90f)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Code128Barcode(data = code.code, height = 110.dp)
                Spacer(Modifier.height(10.dp))
                Text(code.code, fontSize = 18.sp, color = PddSkin.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                if (code.mobileDesc.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code.mobileDesc, fontSize = 16.sp, color = PddSkin.text)
                        Spacer(Modifier.width(4.dp))
                        Text("更换", fontSize = 16.sp, color = PddSkin.link)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("为避免快递丢失，请勿随意分享身份码", fontSize = 14.sp, color = PddSkin.muted)
                Spacer(Modifier.height(8.dp))
                IdentityFooter(code = code, remaining = remaining, onRefresh = onRefresh)
            }
        }
    }
}

@Composable
private fun IdentityFooter(code: PickupCode, remaining: Long, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (remaining > 0) "剩余有效 " + (remaining / 60) + ":" + (remaining % 60).toString().padStart(2, '0')
            else "已过期",
            fontSize = 12.sp,
            color = if (remaining > 0) PddSkin.muted else PddSkin.red,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (remaining > 0) "刷新" else "重新生成",
            fontSize = 13.sp,
            color = PddSkin.link,
            modifier = Modifier.clickable { onRefresh() },
        )
    }
}

private fun qrBitmap(text: String, sizePx: Int) = run {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bitmap.asImageBitmap()
}
