package com.vlesscardvpn
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Render production controls with synthetic samples; no claim of a real Telegram call. */
class RecoveryUiR207Test {
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private fun shell(s:String) {android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(s)).use {it.readBytes()}}
 private fun labels(n:AccessibilityNodeInfo?):List<String> = if(n==null) emptyList() else listOfNotNull(n.text?.toString(),n.contentDescription?.toString())+(0 until n.childCount).flatMap {labels(n.getChild(it))}
 private fun show(name:String,light:Boolean=false,large:Boolean=false,health:Boolean=false,bad:Boolean=false) {
  val originalSettings=Store.state.value.settings
  if(large) {shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5")}
  Store.update {it.copy(settings=it.settings.copy(secureScreen=false,welcomeSound=false,reducedMotion=true,oceanBackground=false,mode=if(health) com.vlesscardvpn.model.Mode.SERVERS else com.vlesscardvpn.model.Mode.AUTO,autoLatencyMinMs=100,autoLatencyMaxMs=800))}
  TelegramStability.clear()
  try {
   ActivityScenario.launch(MainActivity::class.java).use {scenario ->
    scenario.onActivity {a->
     TelegramStability.clear()
     if(health) repeat(5) {TelegramStability.record(if(bad && it==3) false else true,100+it*10,"ui-synthetic")}
     a.setContent {
     MaterialTheme(colorScheme=if(light) lightColorScheme() else darkColorScheme()) {
      Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
       if(health) com.vlesscardvpn.ui.ServiceHealthCard() else com.vlesscardvpn.ui.AutoLatencySettings()
      }}
     }
    }}
    ins.waitForIdleSync();Thread.sleep(1200)
    val expected=if(health) if(bad) "Есть сбои" else "Стабильно в серии" else "Сохранить допуск"
    val end=android.os.SystemClock.elapsedRealtime()+8000
    while(labels(ins.uiAutomation.rootInActiveWindow).none {expected in it} && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(200)
    val text=labels(ins.uiAutomation.rootInActiveWindow);assertTrue("$name: $text",text.any {expected in it})
    val root=File(ins.targetContext.getExternalFilesDir(null),"ui209").apply {mkdirs()}
    File(root,"$name.labels.txt").writeText(text.joinToString("\n"))
    val image=ins.uiAutomation.takeScreenshot() ?: error("No rendered image")
    File(root,"$name.png").outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
   }
  } finally {Store.update {it.copy(settings=originalSettings)};TelegramStability.clear();shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
 }
 @Test fun captureNewLatencyAndStabilityControls() {
  show("latency-dark");show("latency-light",light=true);show("latency-large",large=true)
  show("stability-stable",health=true);show("stability-unstable",health=true,bad=true)
 }
}
