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
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real production controls, synthetic outcomes; no authenticated call or live TUN claim. */
class AutoCheckUiR209Test {
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private fun shell(s:String) {android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(s)).use {it.readBytes()}}
 private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
 private fun text()=nodes(ins.uiAutomation.rootInActiveWindow).flatMap {listOfNotNull(it.text?.toString(),it.contentDescription?.toString())}
 private fun await(expected:String) {val end=android.os.SystemClock.elapsedRealtime()+10000;while(text().none {expected in it} && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(200);assertTrue("Missing $expected: ${text()}",text().any {expected in it})}
 private fun capture(name:String,expected:String) {
  ins.waitForIdleSync();Thread.sleep(700);await(expected)
  val root=File(ins.targetContext.getExternalFilesDir(null),"ui209").apply {mkdirs()}
  File(root,"$name.labels.txt").writeText(text().joinToString("\n"))
  val image=ins.uiAutomation.takeScreenshot() ?: error("No screenshot")
  File(root,"$name.png").outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
 }
 private fun toggle(title:String,id:String,on:Boolean) {
  await("Авто: $title")
  val n=nodes(ins.uiAutomation.rootInActiveWindow).firstOrNull {it.contentDescription?.toString()=="Авто: $title" && it.isCheckable} ?: error("No labelled checkable switch $title")
  assertTrue(n.isVisibleToUser);n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
  val end=android.os.SystemClock.elapsedRealtime()+3000
  while((id in Store.state.value.settings.autoChecks)!=on && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(100)
  assertEquals(on,id in Store.state.value.settings.autoChecks)
 }
 private fun render(scenario:ActivityScenario<MainActivity>,light:Boolean=false,health:Boolean=false,full:Boolean=false) {
  scenario.onActivity {a->a.setContent {MaterialTheme(colorScheme=if(light) lightColorScheme() else darkColorScheme()) {
   Surface(Modifier.fillMaxSize()) {
    if(full) com.vlesscardvpn.ui.SettingsScreen() else Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
     if(health) com.vlesscardvpn.ui.ServiceHealthCard() else com.vlesscardvpn.ui.AutoCheckSettings()
    }
   }
  }}}
  ins.waitForIdleSync();Thread.sleep(1000)
 }
 private fun prepare() {Store.update {it.copy(settings=it.settings.copy(mode=Mode.AUTO,autoChecks=AutoCheckProfile.defaults,autoLatencyMaxMs=1000,secureScreen=false,welcomeSound=false,reducedMotion=true,oceanBackground=false))};ConnectionPassport.clear()}
 private fun scrollTo(target:String) {
  val end=android.os.SystemClock.elapsedRealtime()+20000
  while(text().none {target in it} && android.os.SystemClock.elapsedRealtime()<end) {
   val dm=ins.targetContext.resources.displayMetrics;shell("input swipe ${dm.widthPixels/2} ${dm.heightPixels*8/10} ${dm.widthPixels/2} ${dm.heightPixels*3/10} 500");Thread.sleep(350)
  }
  await(target)
 }
 @Test fun toggleSelectionAndPersistWithoutReconnect() {
  val old=Store.state.value.settings
  try {prepare();ActivityScenario.launch(MainActivity::class.java).use {scenario->
   render(scenario);capture("checks-default-dark","YouTube · медиа")
   assertEquals(AutoCheckProfile.defaults,Store.state.value.settings.autoChecks)
   toggle("YouTube · медиа","youtube",true);toggle("Звонки · UDP-транспорт","voice",false)
   toggle("Telegram · устойчивость серии","stability",false);toggle("Telegram · ответ дата-центра","telegram",false)
   val s=Store.state.value.settings;assertEquals(setOf("discord","youtube"),s.autoChecks)
   assertEquals(s.autoChecks,Settings.fromJson(s.toJson()).autoChecks)
   render(scenario,light=true);capture("checks-custom-light","Что проверять в Авто")
   val p=Probe(100,null,true,discordOk=true)
   assertTrue(AutoCheckProfile.of(s.autoChecks).accepts(p,1000))
   assertFalse(AutoCheckProfile.of(s.autoChecks).accepts(p.copy(ytOk=false),1000))
   AutoCheckEvidence.publish(AutoCheckProfile.of(s.autoChecks),1000,p)
   render(scenario,health=true);capture("checks-results","Discord · gateway")
  }} finally {Store.update {it.copy(settings=old)};ConnectionPassport.clear()}
 }
 @Test fun largeFontControlsAndWarnings() {
  val old=Store.state.value.settings
  try {shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5");prepare()
   ActivityScenario.launch(MainActivity::class.java).use {s->render(s);capture("checks-large-controls","Что проверять в Авто");scrollTo("STUN-сервис Google");capture("checks-large-explanation","STUN-сервис Google")}
  } finally {Store.update {it.copy(settings=old)};ConnectionPassport.clear();shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
 }
 @Test fun actualSettingsScreenContainsProfile() {
  val old=Store.state.value.settings
  try {prepare();ActivityScenario.launch(MainActivity::class.java).use {s->render(s,full=true);scrollTo("Что проверять в Авто");capture("checks-settings-entry","Что проверять в Авто")}}
  finally {Store.update {it.copy(settings=old)};ConnectionPassport.clear()}
 }
}
