package com.qzkt.timetable.ui.setup

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qzkt.timetable.data.AppSettings
import com.qzkt.timetable.ui.MainUiState

/**
 * 首次配置向导。
 *
 * 填学校强智地址 + 学号 + 密码 → 测试连接 → 导入课表。
 * 开学日期可以留空，导入时会用教务系统报告的当前周次倒推。
 */
@Composable
fun SetupScreen(
    settings: AppSettings,
    uiState: MainUiState,
    onTest: (String, String, String, Int, String) -> Unit,
    onImport: (String, String, String, Int, String) -> Unit,
    onSave: (String, String, String, Int, String) -> Unit,
    onOpenWebImport: () -> Unit,
) {
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }
    var weekCount by remember(settings.weekCount) { mutableStateOf(settings.weekCount.toString()) }
    var firstMonday by remember(settings.firstMonday) { mutableStateOf(settings.firstMonday) }
    var showPassword by remember { mutableStateOf(false) }

    val canSubmit = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !uiState.busy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("配置账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "登录后自动导入本学期课表，并定时同步课表变动。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("教务账号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                SetupField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "学校强智地址",
                    placeholder = "http://jwgl.xxx.edu.cn",
                    icon = Icons.Default.Link,
                    supportingText = "填教务系统首页地址即可",
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(10.dp))
                SetupField(
                    value = username,
                    onValueChange = { username = it },
                    label = "学号",
                    icon = Icons.Default.Person,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(10.dp))
                SetupField(
                    value = password,
                    onValueChange = { password = it },
                    label = "密码",
                    icon = Icons.Default.Lock,
                    isPassword = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    supportingText = "只保存在本机，用于登录学校教务系统",
                    imeAction = ImeAction.Done,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("学期", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = weekCount,
                        onValueChange = { input -> weekCount = input.filter { it.isDigit() }.take(2) },
                        label = { Text("学期周数") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = firstMonday,
                        onValueChange = { firstMonday = it },
                        label = { Text("第 1 周周一") },
                        placeholder = { Text("2026-09-07") },
                        singleLine = true,
                        supportingText = { Text("可留空") },
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onTest(baseUrl, username, password, weekCount.toIntOrNull() ?: 20, firstMonday) },
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.busy && uiState.progressTotal == 0) {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("测试连接")
            }

            Button(
                onClick = { onImport(baseUrl, username, password, weekCount.toIntOrNull() ?: 20, firstMonday) },
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (settings.configured) "重新导入" else "导入课表")
            }
        }

        if (uiState.busy && uiState.progressTotal > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "正在导入：第 ${uiState.progressDone} / ${uiState.progressTotal} 周",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (baseUrl.isNotBlank()) {
                    onSave(baseUrl, username, password, weekCount.toIntOrNull() ?: 20, firstMonday)
                }
                onOpenWebImport()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (settings.hasSession) "重新在应用内登录" else "在应用内登录（所有学校通用，推荐）")
        }

        Spacer(Modifier.height(18.dp))

        TipCard()
    }
}

/** 配置向导的统一输入行：左侧图标 + 可选的密码可见切换。 */
@Composable
private fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    placeholder: String? = null,
    supportingText: String? = null,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    keyboardType: KeyboardType? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (isPassword && onTogglePassword != null) {
            {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            null
        },
        visualTransformation = if (isPassword && !showPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                isPassword -> KeyboardType.Password
                keyboardType != null -> keyboardType
                else -> KeyboardType.Text
            },
            imeAction = imeAction,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TipCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("填不对怎么办？", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            listOf(
                "地址就填你平时在电脑浏览器上打开教务系统的那个网址，比如 http://jwgl.xxx.edu.cn。",
                "如果提示「返回的不是 JSON」，说明这所学校没开放移动端 app.do 接口，可以先用电脑抓包或改用网页版导入。",
                "登录失败会直接显示学校返回的原因（密码错误 / 需要验证码 / 仅校内网可访问等）。",
                "第 1 周周一留空时，程序会用教务系统报告的当前周次倒推，一般够准；不对的话到设置页改。",
            ).forEach { line ->
                Text("· $line", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
