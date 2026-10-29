package com.vlesscardvpn

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.*
import com.vlesscardvpn.health.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic UI states, real production Compose rendering. Not a live VPN/TUN assertion. */
@RunWith(AndroidJUnit4::class)
class DashboardUiR190Test {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private fun shell(command:String) {android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).use {it.readBytes()}}
    private fun labels(node:AccessibilityNodeInfo?):List<String> {
        if(node==null) return emptyList()
        return listOfNotNull(node.text?.toString(),node.contentDescription?.toString())+(0 until node.childCount).flatMap {labels(node.getChild(it))}
    }
    private fun capture(name:String,expected:String) {
        ins.waitForIdleSync();Thread.sleep(1200)
        runCatching {ins.uiAutomation.waitForIdle(500,5000)}
        var text=labels(ins.uiAutomation.rootInActiveWindow)
        // Startup can briefly expose no accessibility window even after main-thread idle.
        // Await the expected tree update, never scroll or bypass the visibility assertion.
        val deadline=android.os.SystemClock.elapsedRealtime()+10_000
        while(text.none {expected in it} && android.os.SystemClock.elapsedRealtime()<deadline) {
            Thread.sleep(250);ins.waitForIdleSync();val root=ins.uiAutomation.rootInActiveWindow;root?.refresh();text=labels(root)
        }
        val target=File(ins.targetContext.getExternalFilesDir(null),"ui190").apply {mkdirs()}
        File(target,"$name.labels.txt").writeText(text.joinToString("\n"))
        val bitmap=ins.uiAutomation.takeScreenshot() ?: error("Real rendered screenshot is required")
        File(target,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
        assertTrue("$name must expose $expected: $text",text.any {expected in it})
    }
    private fun scroll(node:AccessibilityNodeInfo?):Boolean {
        if(node==null) return false
        if(node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        return (0 until node.childCount).any {scroll(node.getChild(it))}
    }
    private fun show(name:String,theme:String="dark",state:Tunnel.State=Tunnel.State.IDLE,ok:Boolean?=null,large:Boolean=false) {
        if(large) {shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5");Thread.sleep(1000)}
        Store.update {it.copy(settings=it.settings.copy(theme=theme,welcomeSound=false,reducedMotion=true,
            oceanBackground=false,oceanMotion=false,secureScreen=false,hideServerNames=true))}
        ConnectionPassport.clear()
        if(state==Tunnel.State.CONNECTED) ConnectionPassport.fullModules(ModuleHealthPolicy.assess(
            HealthInput(true,100,true,true,true,if(ok==true) 120 else 700,null),HealthRequirements()))
        Tunnel.status.value=Tunnel.Status(state=state,checkOk=ok,route=if(state==Tunnel.State.CONNECTED) "Стенд · тестовый маршрут" else "",since=System.currentTimeMillis())
        Updater.state.value=Updater.State()
        ActivityScenario.launch(MainActivity::class.java).use {
            val expected=when(state){Tunnel.State.CONNECTING->"Отменить поиск";Tunnel.State.CONNECTED->"Отключить";else->"Подключить"}
            capture(name,expected)
            if(name=="partial") {assertTrue(scroll(ins.uiAutomation.rootInActiveWindow));capture("services","Telegram")}
        }
        if(large) {shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
    }
    @Test fun captureRealDashboardStatesWithoutConnectingToVpn() {
        try {
            show("idle-dark");show("idle-light",theme="light");show("searching",state=Tunnel.State.CONNECTING)
            show("error",state=Tunnel.State.ERROR);show("partial",state=Tunnel.State.CONNECTED,ok=false)
            show("connected",state=Tunnel.State.CONNECTED,ok=true);show("large-narrow",large=true)
        } finally {
            Tunnel.status.value=Tunnel.Status();ConnectionPassport.clear()
            shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")
        }
    }
}
