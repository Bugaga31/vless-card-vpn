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
 private fun shot(name:String,expected:String) {
  ins.waitForIdleSync();Thread.sleep(1200)
  var text=labels(ins.uiAutomation.rootInActiveWindow)
  val deadline=android.os.SystemClock.elapsedRealtime()+10_000
  while(text.none {expected in it} && android.os.SystemClock.elapsedRealtime()<deadline) {
   Thread.sleep(250);ins.waitForIdleSync();val root=ins.uiAutomation.rootInActiveWindow;root?.refresh();text=labels(root)
  }
  assertTrue("$name: $text",text.any {expected in it})
  val dir=File(ins.targetContext.getExternalFilesDir(null),"ui205").apply {mkdirs()}
  File(dir,"$name.labels.txt").writeText(text.joinToString("\n"))
  val bitmap=ins.uiAutomation.takeScreenshot() ?: error("Missing rendered UI")
  File(dir,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
 }
 @Test fun actualProductionWhitelistCardOnOffTorAndLargeFont() {
  Store.update {it.copy(settings=it.settings.copy(welcomeSound=false,secureScreen=false))}
  ActivityScenario.launch(MainActivity::class.java).use {scenario->
   var current by mutableStateOf(Settings())
   scenario.onActivity {a->a.setContent {MaterialTheme {Surface {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {RuWhitelistCard(current) {}}}}}}
   fun show(s:Settings) {scenario.onActivity {a->current=s;a.setContent {MaterialTheme {Surface {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {RuWhitelistCard(current) {}}}}}};ins.waitForIdleSync()}
   try {
    show(Settings());shot("ru-on","Банки, Госуслуги")
    show(Settings(ruWhitelist=false));shot("ru-off","Список выключен")
    show(Settings(torAll=true));shot("ru-tor","Всё через Tor")
    shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5");Thread.sleep(1000)
    show(Settings());shot("ru-large","RU-сервисы напрямую")
    show(Settings(ruAllDirect=true));repeat(8){shell("input swipe 160 540 160 100 100")};shot("ru-geo-large","База Karing")
    shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");Thread.sleep(1000)
    show(Settings(ruAllDirect=true));shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-on","База Karing")
    show(Settings());shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-off","Расширение выключено")
    show(Settings(ruAllDirect=true));repeat(8){shell("input swipe 160 540 160 100 100")};shot("ru-geo-large","База Karing")
    shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");Thread.sleep(1000)
    show(Settings(ruAllDirect=true));shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-on","База Karing")
    show(Settings());shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-off","Расширение выключено")
    show(Settings(ruAllDirect=true));repeat(8){shell("input swipe 160 540 160 100 100")};shot("ru-geo-large","База Karing")
    shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");Thread.sleep(1000)
    show(Settings(ruAllDirect=true));shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-on","База Karing")
    show(Settings());shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-off","Расширение выключено")
    show(Settings(ruAllDirect=true));shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-large","База Karing")
    shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset");Thread.sleep(1000)
    show(Settings(ruAllDirect=true));shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-on","База Karing")
    show(Settings());shell("input swipe 160 540 160 100 500");shell("input swipe 160 540 160 100 500");shot("ru-geo-off","Расширение выключено")
   } finally {shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
  }
 }
}
