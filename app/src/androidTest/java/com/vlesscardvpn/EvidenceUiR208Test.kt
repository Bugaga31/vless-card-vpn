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
import com.vlesscardvpn.health.*
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.tor.TorConnectionPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EvidenceUiR208Test {
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private fun shell(s:String) {android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(s)).use {it.readBytes()}}
 private fun labels(n:AccessibilityNodeInfo?):List<String> = if(n==null) emptyList() else listOfNotNull(n.text?.toString(),n.contentDescription?.toString())+(0 until n.childCount).flatMap {labels(n.getChild(it))}
 private fun show(name:String,tor:Boolean=false,light:Boolean=false,large:Boolean=false,unknown:Boolean=false) {
  val original=Store.state.value.settings
  if(large) {shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5")}
  Store.update {it.copy(settings=it.settings.copy(secureScreen=false,welcomeSound=false,reducedMotion=true,oceanBackground=false))}
  try {ActivityScenario.launch(MainActivity::class.java).use {scenario ->
   scenario.onActivity {a->
    ConnectionPassport.clear()
    ConnectionPassport.fullModules(LiveHealthCoordinator.assess(Probe(100,null,null,tgOk=true,tgMs=100),true,true,true,false,true))
    if(!tor) ConnectionPassport.lightModules(Probe(100,null,null,tgOk=if(unknown) null else false),true,true)
    a.setContent {MaterialTheme(colorScheme=if(light) lightColorScheme() else darkColorScheme()) {
     Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
      if(tor) com.vlesscardvpn.ui.TorSettingsCard(TorConnectionPolicy.automatic(Settings()),{})
      else com.vlesscardvpn.ui.ServiceHealthCard()
     }}
    }}
   }
   ins.waitForIdleSync();Thread.sleep(1200)
   if(tor) {
    fun find(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
     if(n==null) return null
     if(n.text?.toString()?.contains("Маршруты, выходы и мосты")==true) return n
     for(i in 0 until n.childCount) {val hit=find(n.getChild(i));if(hit!=null) return hit}
     return null
    }
    // The production bridge selector is intentionally under the advanced expander.
    val dm=ins.targetContext.resources.displayMetrics
    var clicked=false
    var lastBounds="not found"
    for(attempt in 0 until 20) {
     val node=find(ins.uiAutomation.rootInActiveWindow)
     if(node!=null) {
      val rect=android.graphics.Rect();node.getBoundsInScreen(rect);lastBounds=rect.toString()
      if(node.isVisibleToUser && rect.width()>0 && rect.height()>0 && rect.centerY() in 40 until dm.heightPixels-40) {
       shell("input tap ${rect.centerX()} ${rect.centerY()}")
       ins.waitForIdleSync();Thread.sleep(350)
       clicked=labels(ins.uiAutomation.rootInActiveWindow).any {"Скрыть дополнительные настройки" in it}
       if(clicked) break
      }
     }
     shell("input swipe ${dm.widthPixels/2} ${dm.heightPixels*4/5} ${dm.widthPixels/2} ${dm.heightPixels/3} 300")
     ins.waitForIdleSync();Thread.sleep(150)
    }
    assertTrue("Tor advanced expander must open; bounds=$lastBounds; labels=${labels(ins.uiAutomation.rootInActiveWindow)}",clicked)
    for(i in 0 until 20) {
     if(labels(ins.uiAutomation.rootInActiveWindow).any {"Обязательные мосты" in it}) break
     shell("input swipe ${dm.widthPixels/2} ${dm.heightPixels*4/5} ${dm.widthPixels/2} ${dm.heightPixels/4} 300")
     ins.waitForIdleSync();Thread.sleep(150)
    }
   }
   val expected=if(tor) "Обязательные мосты" else if(unknown) "Не проверено" else "Нет ответа"
   val end=android.os.SystemClock.elapsedRealtime()+8000
   while(labels(ins.uiAutomation.rootInActiveWindow).none {expected in it} && android.os.SystemClock.elapsedRealtime()<end) Thread.sleep(200)
   val text=labels(ins.uiAutomation.rootInActiveWindow);assertTrue("$name: $text",text.any {expected in it})
   if(tor) assertTrue(text.none {"Без мостов · через VLESS" in it})
   val root=File(ins.targetContext.getExternalFilesDir(null),"ui208").apply {mkdirs()}
   File(root,"$name.labels.txt").writeText(text.joinToString("\n"))
   val image=ins.uiAutomation.takeScreenshot() ?: error("No image")
   File(root,"$name.png").outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
  }} finally {ConnectionPassport.clear();Store.update {it.copy(settings=original)};shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
 }
 @Test fun renderCorrectedEvidenceAndMandatoryTorBridges() {
  show("telegram-fresh-failure");show("telegram-fresh-unknown",unknown=true)
  show("tor-required-dark",tor=true);show("tor-required-light",tor=true,light=true);show("tor-required-large",tor=true,large=true)
 }
}
