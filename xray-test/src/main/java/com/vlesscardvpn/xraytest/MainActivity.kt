package com.vlesscardvpn.xraytest

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var pending: Intent? = null
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = pending; pending = null
        if (result.resultCode == RESULT_OK && intent != null) startVpn(intent)
        else TestState.update(Session(message = "Разрешение VPN не выдано"))
    }
    private fun startVpn(intent: Intent) {
        try { ContextCompat.startForegroundService(this, intent) }
        catch (e: Exception) { Reports.error(e); TestState.update(Session(message = "Android не разрешил запуск. Открой отчёт.")) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFE1C78F), background = Color(0xFF101614), surface = Color(0xFF1B2621))) {
                val status by TestState.session.collectAsState()
                val nodes by TestState.profiles.collectAsState()
                var input by remember { mutableStateOf("") }
                var error by remember { mutableStateOf("") }
                var auto by remember { mutableStateOf(true) }
                var consent by remember { mutableStateOf(false) }
                var selected by remember { mutableStateOf(0) }
                var report by remember { mutableStateOf<String?>(null) }
                val clipboard = LocalClipboardManager.current
                val occupied = status.busy || status.active
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("PRIVATE LOUNGE / XRAY TEST", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        Text("Проверяем связь.", fontFamily = FontFamily.Serif, fontSize = 34.sp)
                        Text("${BuildConfig.VERSION_NAME} · отдельное тестовое приложение. Старые настройки не изменяются.")
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF233D31), Color(0xFF162019)))).padding(20.dp)) {
                            Text(status.message, fontSize = 20.sp)
                            if (status.busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                        Text("Импортируй свои VLESS-ссылки: по одной на строку. TCP / WebSocket / gRPC; TLS или REALITY. Ссылки хранятся только в памяти тестового приложения, до завершения процесса.")
                        OutlinedTextField(value = input, onValueChange = { if (it.length <= 262144) input = it }, label = { Text("VLESS-ссылки") }, enabled = !occupied,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                        OutlinedButton(onClick = {
                            try { TestState.profiles.value = XrayConfig.parseList(input); selected = 0; input = ""; error = "" }
                            catch (e: ProfileError) { error = e.explanation }
                        }, enabled = !occupied) { Text("Импортировать · сейчас ${nodes.size}") }
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                        Row { Checkbox(checked = auto, enabled = !occupied, onCheckedChange = { auto = it }); Text("Авто: перебрать импортированные серверы и искать замену после повторных ошибок") }
                        if (!auto && nodes.isNotEmpty()) {
                            OutlinedButton(onClick = { selected = (selected + 1) % nodes.size }, enabled = !occupied) { Text("Выбран сервер ${selected + 1} · нажми для следующего") }
                        }
                        Row { Checkbox(checked = consent, enabled = !occupied, onCheckedChange = { consent = it }); Text("Разрешаю HTTPS-проверки telegram.org и YouTube через серверы. Авто может переключать соединение и прерывать сессии.") }
                        Text("Проверяется веб-доступ, не сообщения Telegram и не видео. Приложение исключено из VPN во избежание петли; проверки явно идут через SOCKS Xray. Другие приложения идут через TUN. Это не kill switch.", fontSize = 12.sp)
                        Button(onClick = {
                            val intent = Intent(this@MainActivity, XrayVpnService::class.java).setAction("START").putExtra("auto", auto).putExtra("consent", consent).putExtra("selected", selected)
                            val request = VpnService.prepare(this@MainActivity)
                            if (request == null) startVpn(intent) else { pending = intent; permission.launch(request) }
                        }, enabled = nodes.isNotEmpty() && consent && !occupied, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (auto) "Подключиться автоматически" else "Подключить выбранный") }
                        OutlinedButton(onClick = {
                            try { startService(Intent(this@MainActivity, XrayVpnService::class.java).setAction("STOP")) }
                            catch (e: Exception) { Reports.error(e) }
                        }, enabled = occupied, modifier = Modifier.fillMaxWidth()) { Text("Отключить / отменить") }
                        OutlinedButton(onClick = { report = Reports.read(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) { Text("Отчёт о подключении / вылете") }
                        Text("Если приложение закроется: открой его снова → Отчёт. Отчёт не содержит исходных ссылок и текста ошибок ядра; проверь его перед отправкой.", fontSize = 12.sp)
                        Text("Xray-core / AndroidLibXrayLite v26.8.20. Источники и лицензии: xray-test/THIRD_PARTY.md в репозитории Bugaga31/vless-card-vpn.", fontSize = 12.sp)
                    }
                    report?.let { body -> AlertDialog(onDismissRequest = { report = null }, title = { Text("Отчёт тестовой сборки") }, text = {
                        Text(body, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp)
                    }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(body)); report = null }) { Text("Скопировать") } }, dismissButton = { TextButton(onClick = { report = null }) { Text("Закрыть") } }) }
                }
            }
        }
    }
}
