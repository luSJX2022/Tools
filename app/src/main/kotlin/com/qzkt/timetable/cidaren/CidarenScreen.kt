@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.qzkt.timetable.cidaren

import com.qzkt.timetable.data.PluginLog
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 四级词库（原生 Compose 版）。
 *
 * 设计上的三点取舍：
 *  - 词库与题库只在进入这一屏时读取一次（assets 里的 data.json 约 600KB，
 *    每次重组都解析会明显卡顿），随后交给 CidarenEngine 这个进程级单例做索引。
 *  - 页面切换用一个 rememberSaveable 的下标，而不是 NavHost：这一屏是纯本地功能，
 *    没有深链接需求，用下标切换可以避免给 route 层加参数。
 *  - 持久化统一走 CidarenStore，所有写入都经过 [updateContent] 这一个口子（先改内存再落盘），
 *    免得某个分支改了状态忘了 save() 导致重启后数据丢失。
 *
 * 主题完全跟随宿主：不提供任何主题开关，配色一律取 MaterialTheme.colorScheme。
 */

/** 正确项的语义绿：不跟随主色，但要同时在浅色 / 深色背景上可读，所以取中间调。 */
private val CorrectGreen = Color(0xFF2E9E5B)

/** 顶部页签；顺序与 when 分支一一对应。 */
private val TABS = listOf("练习", "四级词库", "错题本", "设置")

/** 速查页的筛选范围。 */
private const val SCOPE_CET4 = "四级"
private const val SCOPE_CUSTOM = "自定义"

@Composable
fun CidarenScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current

    val store = remember { CidarenStore(context) }
    // 只读一次本地状态；后面全程用 state 这份内存副本，改完立刻回写
    val saved = remember { store.load() }
    var state by remember { mutableStateOf(saved) }

    // 数据包约 1MB（精讲 851 条 + 完整四级词库 4536 条），解析 JSON 与建索引都不该占着主线程，
    // 所以放 IO 线程装载，界面先显示加载态；装好之前下面的界面不渲染。
    var data by remember { mutableStateOf(CidarenData()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { CidarenData.load(context) }.getOrElse { CidarenData() }
        }
        data = loaded
        loading = false
    }
    // 装载完成或自定义词库变了（导入 / 清空）时重建索引，同样放 IO
    LaunchedEffect(data, state.custom) {
        if (data.words.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) { CidarenEngine.setData(data, state.custom) }
    }

    fun updateContent(block: (CidarenStore.State) -> CidarenStore.State) {
        val next = block(state)
        state = next
        store.save(next)
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    // 词库只展示四级大纲词表与用户自定义词条。
    val allWords = remember(state.custom, data) {
        (state.custom + data.cet4).distinctBy { it.w.lowercase() }
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在装载词库…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("四级词库") },
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
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TABS.forEachIndexed { i, name ->
                    FilterChip(
                        selected = tab == i,
                        onClick = { tab = i },
                        label = { Text(name, fontSize = 12.sp) },
                    )
                }
            }

            when (tab) {
                0 -> PracticePage(
                    questions = data.questions,
                    state = state,
                    onUpdate = { block -> updateContent(block) },
                )

                1 -> LookupPage(
                    words = allWords,
                    cet4Count = data.cet4.size,
                    customCount = state.custom.size,
                )

                2 -> WrongBookPage(
                    state = state,
                    onUpdate = { block -> updateContent(block) },
                )

                else -> SettingsPage(
                    data = data,
                    state = state,
                    onUpdate = { block -> updateContent(block) },
                )
            }
        }
    }
}

/** 练习页的题目讲解卡片。 */
@Composable
private fun ResultCard(r: CidarenResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tag(
                    text = r.typeLabel.ifBlank { "词汇题" },
                    background = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Tag(
                    text = if (r.source == "bank") "内置题库原题" else "规则推断",
                    background = MaterialTheme.colorScheme.secondaryContainer,
                    foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Tag(
                    text = "置信度 " + (r.confidence * 100).roundToInt() + "%（" + r.confidenceLabel + "）",
                    background = MaterialTheme.colorScheme.tertiaryContainer,
                    foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            val stem = r.stemPlain.ifBlank { r.stem }
            if (stem.isNotBlank()) {
                Text(stem, style = MaterialTheme.typography.bodyMedium)
            }

            if (r.options.isNotEmpty()) {
                r.options.forEachIndexed { index, option ->
                    // 优先采信分析里的 correct；早期结果没有分析时退回 answerIndex
                    val correct = r.analysis.firstOrNull { it.label == option.label }?.correct
                        ?: (index == r.answerIndex)
                    OptionLine(option.label, option.text, correct)
                }
            }

            if (r.answerText.isNotBlank() || r.entry != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "答案",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (r.answerIndex >= 0) {
                        ('A'.code + r.answerIndex).toChar().toString() + ". " + r.answerText
                    } else {
                        r.answerText
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val entry = r.entry
                if (entry != null) {
                    // 选项是「was admitted」这类短语时，答案文本和词条不是同一个词，
                    // 这时把词条标注成「考点词」，否则用户会以为答错了
                    val same = normalizeWord(entry.w) == normalizeWord(r.answerText)
                    val meaning = listOfNotNull(
                        entry.pos.takeIf { it.isNotBlank() },
                        entry.cn.takeIf { it.isNotBlank() },
                    ).joinToString(" ")
                    Text(
                        text = (if (same) "" else "考点词 " + entry.w + "：") + meaning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (r.kp.isNotBlank()) LabeledText("考点", r.kp)
            if (r.structure.isNotBlank()) LabeledText("句意/结构", r.structure)

            if (r.analysis.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text("逐项分析", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                r.analysis.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            text = if (item.correct) "●" else "○",
                            color = if (item.correct) CorrectGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.width(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.head,
                                fontSize = 13.sp,
                                fontWeight = if (item.correct) FontWeight.Bold else FontWeight.Normal,
                                color = if (item.correct) CorrectGreen else MaterialTheme.colorScheme.onSurface,
                            )
                            if (item.body.isNotBlank()) {
                                Text(
                                    text = item.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            r.blocks.forEach { block ->
                Text(
                    text = "【" + block.title + "】",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                block.lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (r.error.isNotBlank()) WarmTip(r.error)
            r.warnings.forEach { WarmTip(it) }
        }
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 练习页的选项行：正确项绿色高亮，分析里 correct 的用 ●，其它 ○。 */
@Composable
private fun OptionLine(label: String, text: String, correct: Boolean) {
    val foreground = if (correct) CorrectGreen else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (correct) CorrectGreen.copy(alpha = 0.14f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (correct) "●" else "○",
            color = foreground,
            fontSize = 13.sp,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = label + ". " + text,
            color = foreground,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 暖色提示条：warnings 与错误都走这里，颜色取主题的 tertiary 容器，深浅色都协调。 */
@Composable
private fun WarmTip(text: String) {
    Text(
        text = "⚠ " + text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

/* ==================== 2. 练习 ==================== */

/** 题型过滤：把引擎的细粒度 type 收敛成四个用户能理解的类别。 */
private fun matchTypeFilter(type: String, filter: Int): Boolean = when (filter) {
    0 -> true
    1 -> type == "choice-meaning" || type == "choice-word" || type == "match" ||
        type == "lookup" || type == "lookup-text" || type == "reverse"
    2 -> type == "cloze" || type == "cloze-naked"
    3 -> type == "spelling"
    4 -> type == "synonym" || type == "antonym" || type == "mixed"
    else -> true
}

@Composable
private fun PracticePage(
    questions: List<CidarenQuestion>,
    state: CidarenStore.State,
    onUpdate: ((CidarenStore.State) -> CidarenStore.State) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(0) }
    // 每点一次「下一题」就自增，作为重新抽题的信号（random 本身不参与重组比较）
    var nonce by rememberSaveable { mutableStateOf(0) }
    var question by remember { mutableStateOf<CidarenQuestion?>(null) }
    var picked by remember { mutableStateOf(-1) }
    var submitted by remember { mutableStateOf(false) }
    var explanation by remember { mutableStateOf<CidarenResult?>(null) }

    LaunchedEffect(questions, filter, nonce) {
        question = questions.filter { matchTypeFilter(it.type, filter) }.randomOrNull()
        picked = -1
        submitted = false
        explanation = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("全部", "词义", "填空", "拼写", "辨析").forEachIndexed { i, name ->
                FilterChip(
                    selected = filter == i,
                    onClick = { filter = i },
                    label = { Text(name, fontSize = 12.sp) },
                )
            }
        }

        Text(
            text = "已答 " + state.answered + " 题 · 答对 " + state.correct +
                " 题 · 正确率 " + rateOf(state.answered, state.correct) + "%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val q = question
        if (q == null) {
            Text(
                text = "当前题型下没有题目，换一个筛选试试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Section(if (submitted) "作答结果" else "题目") {
                Text(q.stem, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))

                q.options.forEachIndexed { i, option ->
                    ChoiceRow(
                        label = ('A'.code + i).toChar().toString(),
                        text = option,
                        chosen = picked == i,
                        correct = submitted && i == q.answer,
                        wrong = submitted && picked == i && i != q.answer,
                        enabled = !submitted,
                        onClick = { picked = i },
                    )
                }

                Spacer(Modifier.height(8.dp))
                if (!submitted) {
                    Button(
                        onClick = {
                            val right = picked == q.answer
                            val text = buildString {
                                append(q.stem)
                                q.options.forEachIndexed { i, option ->
                                    appendLine(('A'.code + i).toChar().toString() + ". " + option)
                                }
                            }
                            val res = CidarenEngine.analyze(text)
                            PluginLog.i(
                                "四级词库",
                                "练习作答：题型=" + res.typeLabel + "，结果=" + (if (right) "正确" else "错误") +
                                    (if (res.ok) "" else "（未识别：" + res.warnings.joinToString("；").take(80) + "）"),
                            )
                            explanation = res
                            submitted = true
                            onUpdate { old ->
                                old.copy(
                                    answered = old.answered + 1,
                                    correct = old.correct + if (right) 1 else 0,
                                    // 同一题干反复做错只记一条，避免错题本被同一题刷屏
                                    wrong = if (right || old.wrong.any { it.stem == q.stem }) {
                                        old.wrong
                                    } else {
                                        old.wrong + CidarenStore.Wrong(
                                            stem = q.stem,
                                            answerText = q.options.getOrElse(q.answer) { "" },
                                            typeLabel = res.typeLabel,
                                            time = nowText(),
                                        )
                                    },
                                )
                            }
                        },
                        enabled = picked >= 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("提交答案") }
                } else {
                    val right = picked == q.answer
                    val answerLine = if (q.answer in q.options.indices) {
                        ('A'.code + q.answer).toChar().toString() + ". " + q.options[q.answer]
                    } else {
                        "见下方解析"
                    }
                    Text(
                        text = if (right) "✓ 回答正确" else "✗ 回答错误，正确答案：" + answerLine,
                        color = if (right) CorrectGreen else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { nonce++ },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("下一题") }
                }
            }

            explanation?.let { ResultCard(it) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** 练习页的选项行：可点，提交后正确项绿、错选项红。 */
@Composable
private fun ChoiceRow(
    label: String,
    text: String,
    chosen: Boolean,
    correct: Boolean,
    wrong: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        correct -> CorrectGreen.copy(alpha = 0.16f)
        wrong -> MaterialTheme.colorScheme.errorContainer
        chosen -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val foreground = when {
        correct -> CorrectGreen
        wrong -> MaterialTheme.colorScheme.onErrorContainer
        chosen -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = foreground,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (correct) {
            Text("✓", color = CorrectGreen, fontWeight = FontWeight.Bold)
        } else if (wrong) {
            Text("✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}

/* ==================== 3. 速查 ==================== */

@Composable
private fun LookupPage(
    words: List<CidarenWord>,
    cet4Count: Int = 0,
    customCount: Int = 0,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<CidarenWord?>(null) }
    var scope by rememberSaveable { mutableStateOf(SCOPE_CET4) }
    var letter by rememberSaveable { mutableStateOf("") }

    val scopes = listOf(SCOPE_CET4 to cet4Count, SCOPE_CUSTOM to customCount)

    val matched = remember(query, words, scope, letter) {
        val text = query.trim()
        val lower = text.lowercase()
        val hasCjk = text.any { it.code in 0x4E00..0x9FFF }
        val byScope = when (scope) {
            SCOPE_CUSTOM -> words.filter { it.custom }
            else -> words.filter { it.level == "四级" && !it.custom }
        }
        val byLetter = if (letter.isEmpty()) byScope else byScope.filter { it.w.startsWith(letter, ignoreCase = true) }
        if (text.isBlank()) byLetter
        else byLetter.filter { word ->
            if (hasCjk) {
                // 中文用引擎的二元组相似度做粗糙匹配，够用且不用另建索引
                word.cn.contains(text) || CidarenEngine.cnSim(text, word.cn) >= 0.25
            } else {
                word.w.lowercase().startsWith(lower) || word.w.lowercase().contains(lower)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            label = { Text("搜单词或中文释义") },
            singleLine = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            scopes.forEach { (name, count) ->
                FilterChip(
                    selected = scope == name,
                    onClick = { scope = name },
                    label = { Text(if (count > 0) name + " " + count else name) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(selected = letter.isEmpty(), onClick = { letter = "" }, label = { Text("全部字母") })
            ('A'..'Z').forEach { c ->
                val s = c.toString()
                FilterChip(selected = letter == s, onClick = { letter = if (letter == s) "" else s }, label = { Text(s) })
            }
        }
        Text(
            text = "四级词库 " + cet4Count + " 条 · 自定义 " + customCount +
                " 条 · 当前 " + matched.size + " 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(matched, key = { it.w }) { word ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { detail = word },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(word.w, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (word.custom) {
                                    Spacer(Modifier.width(6.dp))
                                    Tag(
                                        text = "自定义",
                                        background = MaterialTheme.colorScheme.tertiaryContainer,
                                        foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                            Text(
                                text = listOfNotNull(
                                    word.pos.takeIf { it.isNotBlank() },
                                    word.cn.takeIf { it.isNotBlank() },
                                ).joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    val current = detail
    if (current != null) {
        WordDetailDialog(current) { detail = null }
    }
}

@Composable
private fun WordDetailDialog(word: CidarenWord, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = {
            Text(
                text = word.w + if (word.custom) "（自定义）" else "",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (word.ph.isNotBlank()) DetailLine("音标", word.ph)
                if (word.pos.isNotBlank() || word.cn.isNotBlank()) {
                    DetailLine("释义", (word.pos + " " + word.cn).trim())
                }
                // 词库自带词根就用词库的；没有才让引擎现场拆（拆不出返回 null，不编造词源）
                val root = if (word.root.isNotEmpty()) {
                    word.root.joinToString(" + ") { it.p + "（" + it.m + "）" }
                } else {
                    CidarenEngine.decompose(word.w)
                }
                if (!root.isNullOrBlank()) DetailLine("词根词缀", root)
                if (word.colloc.isNotEmpty()) DetailLine("常用搭配", word.colloc.joinToString("；"))
                if (word.ex.en.isNotBlank()) {
                    DetailLine(
                        "例句",
                        word.ex.en + if (word.ex.cn.isNotBlank()) "　" + word.ex.cn else "",
                    )
                }
                if (word.confuse.isNotEmpty()) {
                    DetailLine("易混辨析", word.confuse.joinToString("；") { it.w + "：" + it.d })
                }
                val tags = (listOf(word.level) + word.tag).filter { it.isNotBlank() }
                if (tags.isNotEmpty()) DetailLine("标签", tags.joinToString(" · "))
                if (word.note.isNotBlank()) DetailLine("备注", word.note)
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/* ==================== 4. 错题本 ==================== */

@Composable
private fun WrongBookPage(
    state: CidarenStore.State,
    onUpdate: ((CidarenStore.State) -> CidarenStore.State) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var reviewItem by remember { mutableStateOf<CidarenStore.Wrong?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "共 " + state.wrong.size + " 条错题",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.wrong.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("清空") }
            }
        }

        if (state.wrong.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "还没有错题，去「练习」做几道吧。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.wrong) { index, item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = item.stem,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "答案：" + item.answerText +
                                    if (item.typeLabel.isNotBlank()) " · " + item.typeLabel else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (item.time.isNotBlank()) {
                                Text(
                                    text = item.time,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { reviewItem = item }) { Text("复习") }
                                TextButton(onClick = {
                                    onUpdate { old ->
                                        old.copy(wrong = old.wrong.filterIndexed { i, _ -> i != index })
                                    }
                                }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空错题本") },
            text = { Text("将删除全部 " + state.wrong.size + " 条错题，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate { it.copy(wrong = emptyList()) }
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    reviewItem?.let { item ->
        AlertDialog(
            onDismissRequest = { reviewItem = null },
            title = { Text("错题复习") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.stem, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "答案：" + item.answerText +
                            if (item.typeLabel.isNotBlank()) " · " + item.typeLabel else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { reviewItem = null }) { Text("知道了") }
            },
        )
    }
}

/* ==================== 5. 设置 ==================== */

@Composable
private fun SettingsPage(
    data: CidarenData,
    state: CidarenStore.State,
    onUpdate: ((CidarenStore.State) -> CidarenStore.State) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pasteOpen by remember { mutableStateOf(false) }
    var pasteDraft by remember { mutableStateOf("") }

    /** 导入得到的词条：合并 → 落盘 → 立刻并进引擎（否则要等下一次重组才生效）。 */
    fun applyImport(incoming: List<CidarenWord>) {
        if (incoming.isEmpty()) {
            PluginLog.w("四级词库", "词库导入：没有解析到词条")
            Toast.makeText(context, "没有解析到词条，检查文件格式", Toast.LENGTH_SHORT).show()
            return
        }
        val merged = CidarenGlossary.merge(state.custom, incoming)
        PluginLog.i("四级词库", "词库导入：新增 " + incoming.size + " 条，合计 " + merged.size + " 条")
        onUpdate { it.copy(custom = merged) }
        CidarenEngine.setData(data, merged)
        Toast.makeText(context, "已导入 " + incoming.size + " 条，共 " + merged.size + " 条", Toast.LENGTH_SHORT).show()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                    }.getOrNull()
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(context, "读取文件失败或文件为空", Toast.LENGTH_SHORT).show()
                } else {
                    applyImport(CidarenGlossary.parse(text))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /* ---------- 自定义词库 ---------- */
        Section("词库") {
            Text("四级词库 " + data.cet4.size + " 条（大学英语四级大纲词表）· 精讲 " + data.rich.size + " 条", fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "内置词库共 " + data.words.size + " 条：精讲词条带音标、搭配、例句与词根词缀，四级表补全其余大纲词。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        /* ---------- 自定义词库 ---------- */
        Section("自定义词库") {
            Text("当前 " + state.custom.size + " 条", fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "自定义词条优先于内置词库；支持 JSON 数组，或每行「单词 释义」（也认逗号 / 制表符分隔）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    // 不同文件管理器给 CSV / JSON 的 mime 五花八门，末尾放 */* 兜底
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "text/csv", "*/*"),
                    )
                }) { Text("导入文件") }
                TextButton(onClick = {
                    pasteDraft = ""
                    pasteOpen = true
                }) { Text("粘贴导入") }
                if (state.custom.isNotEmpty()) {
                    TextButton(onClick = { onUpdate { it.copy(custom = emptyList()) } }) { Text("清空") }
                }
            }
        }

        /* ---------- 数据统计 ---------- */
        Section("数据统计") {
            Text(
                text = "已答 " + state.answered + " 题 · 答对 " + state.correct +
                    " 题 · 正确率 " + rateOf(state.answered, state.correct) + "%",
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "错题 " + state.wrong.size + " 条 · 自定义词库 " + state.custom.size + " 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (pasteOpen) {
        AlertDialog(
            onDismissRequest = { pasteOpen = false },
            title = { Text("粘贴导入词库") },
            text = {
                Column {
                    Text(
                        text = "每行一条：「abandon 放弃；抛弃」或「abandon,放弃」，也支持 JSON。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pasteDraft,
                        onValueChange = { pasteDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("词条文本") },
                        minLines = 6,
                        maxLines = 10,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applyImport(CidarenGlossary.parse(pasteDraft))
                    pasteOpen = false
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("取消") } },
        )
    }
}

/* ==================== 通用小组件 ==================== */

/** 一张卡片 = 一个功能区，和工程里其他设置页的观感保持一致。 */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 短标签（题型 / 来源 / 免 Key…）。用纯色块而不是 Chip，避免 Chip 自带的最小高度撑开密排行。 */
@Composable
private fun Tag(text: String, background: Color, foreground: Color) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 只保留字母并转小写，用来比较「答案文本」和「词条」是不是同一个词。 */
private fun normalizeWord(text: String): String = text.lowercase().filter { it in 'a'..'z' }

private fun rateOf(answered: Int, correct: Int): Int =
    if (answered > 0) (correct * 100.0 / answered).roundToInt() else 0

private fun nowText(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
