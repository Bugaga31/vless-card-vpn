package com.vlesscardvpn

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** UI consent on a disposable fixture only; never user's data. */
class MaintenanceUiR190Test {
 private val ins get()=InstrumentationRegistry.getInstrumentation()
 private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null) emptyList() else listOf(n)+(0 until n.childCount).flatMap {nodes(n.getChild(it))}
 private fun text(n:AccessibilityNodeInfo)=listOfNotNull(n.text?.toString(),n.contentDescription?.toString()).joinToString(" ")
 private fun click(label:String) {
  val deadline=android.os.SystemClock.elapsedRealtime()+8000
  while(android.os.SystemClock.elapsedRealtime()<deadline) {
   val n=nodes(ins.uiAutomation.rootInActiveWindow).firstOrNull {label in text(it)}
   if(n!=null) {var p:AccessibilityNodeInfo?=n;repeat(5) {val node=p;if(node!=null && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;p=node?.parent}}
   Thread.sleep(200)
  };error("Visible action missing: $label")
 }
 private fun capture(name:String,expected:String) {
  ins.waitForIdleSync();Thread.sleep(800)
  val deadline=android.os.SystemClock.elapsedRealtime()+8000
  while(nodes(ins.uiAutomation.rootInActiveWindow).none {expected in text(it)} && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(200)
  assertTrue(nodes(ins.uiAutomation.rootInActiveWindow).any {expected in text(it)})
  val root=File(ins.targetContext.getExternalFilesDir(null),"ui204").apply {mkdirs()}
  val image=ins.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
  File(root,"$name.png").outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
 }
 @Test fun previewCancelConfirmAndProtectedData() {
  val ctx=ins.targetContext
  val fixture=File(ctx.cacheDir,"maintenance-ui-fixture.tmp").apply {writeText("disposable fixture");check(setLastModified(System.currentTimeMillis()-2*CacheMaintenance.MIN_AGE_MS))}
  val protected=File(ctx.cacheDir,"maintenance-ui-protected.apk").apply {writeText("protected fixture");setLastModified(System.currentTimeMillis()-2*CacheMaintenance.MIN_AGE_MS)}
  try {
   Store.update {it.copy(settings=it.settings.copy(theme="dark",welcomeSound=false,reducedMotion=true,oceanBackground=false,secureScreen=false))}
   Tunnel.status.value=Tunnel.Status()
   ActivityScenario.launch(MainActivity::class.java).use {
    click("Телефон");click("Проверить временные файлы");capture("maintenance-preview","Можно удалить")
    assertTrue(fixture.exists());click("Просмотреть и удалить");capture("maintenance-confirm","Удалить старые временные файлы?")
    click("Отмена");assertTrue(fixture.exists());assertTrue(protected.exists())
    click("Просмотреть и удалить");click("Удалить временные файлы");capture("maintenance-result","Удалено")
    assertFalse(fixture.exists());assertTrue(protected.exists())
   }
  } finally {fixture.delete();protected.delete()}
 }
}
