package com.vlesscardvpn

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.ui.RuWhitelistCard
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuWhitelistUiR192Test {
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private fun shell(s:String) {android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(s)).use {it.readBytes()}}
 private fun labels(n:AccessibilityNodeInfo?):List<String> = if(n==null) emptyList() else listOfNotNull(n.text?.toString())+(0 until n.childCount).flatMap {labels(n.getChild(it))}
 private fun shot(name:String,expected:String,seek:Boolean=false) {
  ins.waitForIdleSync();Thread.sleep(1200)
  var text=labels(ins.uiAutomation.rootInActiveWindow)
  val deadline=android.os.SystemClock.elapsedRealtime()+30_000
  while(text.none {expected in it} && android.os.SystemClock.elapsedRealtime()<deadline) {
   if(seek) {
    val dm=ins.targetContext.resources.displayMetrics
    shell("input swipe ${dm.widthPixels/2} ${dm.heightPixels*4/5} ${dm.widthPixels/2} ${dm.heightPixels/2} 600")
   }
   Thread.sleep(500);ins.waitForIdleSync();val root=ins.uiAutomation.rootInActiveWindow;root?.refresh();text=labels(root)
  }
  assertTrue("$name: $text",text.any {expected in it})
  val dir=File(ins.targetContext.getExternalFilesDir(null),"ui209").apply {mkdirs()}
  File(dir,"$name.labels.txt").writeText(text.joinToString("\n"))
  val bitmap=ins.uiAutomation.takeScreenshot() ?: error("Missing rendered UI")
  File(dir,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
 }
 @Test fun actualProductionWhitelistCardOnOffTorAndLargeFont() {
  val original=Store.state.value.settings
  Store.update {it.copy(settings=it.settings.copy(welcomeSound=false,secureScreen=false))}
  try {ActivityScenario.launch(MainActivity::class.java).use {scenario->
   var generation by mutableStateOf(0)
   fun show(s:Settings) {
    generation++
    scenario.onActivity {a->a.setContent {
     key(generation) {MaterialTheme {Surface {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {RuWhitelistCard(s) {}}
     }}}
    }}
    ins.waitForIdleSync()
   }
   show(Settings());shot("ru-on","Банки, Госуслуги")
   show(Settings(ruWhitelist=false));shot("ru-off","Список выключен")
   show(Settings(torAll=true));shot("ru-tor","Всё через Tor")
   shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5");Thread.sleep(1000)
   show(Settings());shot("ru-large","RU-сервисы напрямую")
   show(Settings(ruAllDirect=true));shot("ru-geo-large","База Karing",seek=true)
   shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");Thread.sleep(1000)
   show(Settings(ruAllDirect=true));shot("ru-geo-on","База Karing",seek=true)
   show(Settings());shot("ru-geo-off","Расширение выключено",seek=true)
  }} finally {Store.update {it.copy(settings=original)};shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
 }
}
