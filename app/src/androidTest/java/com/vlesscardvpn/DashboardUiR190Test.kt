package com.vlesscardvpn

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
        val target=File(ins.targetContext.getExternalFilesDir(null),"ui198").apply {mkdirs()}
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
    private fun show(name:String,theme:String="dark",state:Tunnel.State=Tunnel.State.IDLE,ok:Boolean?=null,large:Boolean=false,wait:AutoSearchCampaign.WaitState?=null,background:BackgroundOptimization.Stage?=null) {
        if(large) {shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5");Thread.sleep(1000)}
        Store.update {it.copy(settings=it.settings.copy(theme=theme,welcomeSound=false,reducedMotion=true,
            oceanBackground=false,oceanMotion=false,secureScreen=false,hideServerNames=true))}
        ConnectionPassport.clear()
        if(state==Tunnel.State.CONNECTED) ConnectionPassport.fullModules(ModuleHealthPolicy.assess(
            HealthInput(true,100,true,true,true,if(ok==true) 120 else 700,null),HealthRequirements()))
        Tunnel.status.value=Tunnel.Status(state=state,checkOk=ok,check=if(name=="network-recovery") NetworkRecoveryPresentation.message("Моб.: LTE") else "",route=if(state==Tunnel.State.CONNECTED) "Стенд · тестовый маршрут" else "",since=System.currentTimeMillis())
        Updater.state.value=Updater.State()
        Actions.progress.value=if(state==Tunnel.State.CONNECTING) Actions.Progress(title="Проверяем доступность",running=true,
            message="HTTPS и Telegram проверяются параллельно") else Actions.Progress()
        BackgroundOptimization.clear()
        if(background!=null) BackgroundOptimization.show(background)
        if(wait!=null) Actions.progress.value=Actions.Progress(title=SearchFeedback.title(wait),running=true,
            message=SearchFeedback.message(wait,mapOf("completed" to 17,"unfinished" to 3)))
        ActivityScenario.launch(MainActivity::class.java).use {
            val expected=when(state){Tunnel.State.CONNECTING->"Отменить поиск";Tunnel.State.CONNECTED->"Отключить";else->"Подключить"}
            if(wait!=null||background!=null) shell("input swipe 160 500 160 300 400")
            capture(name,if(wait!=null) SearchFeedback.title(wait) else if(background!=null) BackgroundOptimization.message(background) else expected)
            if(name=="searching") {assertTrue(scroll(ins.uiAutomation.rootInActiveWindow));capture("search-progress","Как подключаться")}
            if(name=="partial") {assertTrue(scroll(ins.uiAutomation.rootInActiveWindow));capture("services","Telegram")}
        }
        if(large) {shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
    }
    private fun manualProgress(name:String,large:Boolean=false) {
        Store.update {it.copy(settings=it.settings.copy(theme="dark",secureScreen=false,welcomeSound=false,reducedMotion=true,oceanBackground=false))}
        if(large) {shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5");Thread.sleep(1000)}
        Actions.progress.value=Actions.Progress(title="Ручная проверка серверов",running=true,done=3,total=12,
            message="Проверяем HTTPS и Telegram. Текущий маршрут остаётся подключён.")
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {
                MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF8FC2FF),background=Color(0xFF10151C),
                    surface=Color(0xFF1A222E),onSurface=Color(0xFFF0F6FC),onSurfaceVariant=Color(0xFFB3C6D5))) {
                    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                            com.vlesscardvpn.ui.LiveProgress(stop=true)
                        }
                    }
                }
            }}
            capture(name,"Остановить проверку")
        }
        Actions.progress.value=Actions.Progress()
        if(large) {shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
    }
    private fun torView(name:String,large:Boolean=false,preferences:Boolean=false,country:String?="DE") {
        Store.update {it.copy(settings=it.settings.copy(theme="dark",secureScreen=false,welcomeSound=false,reducedMotion=true,oceanBackground=false))}
        if(large) {shell("wm size 720x1600");shell("wm density 360");shell("settings put system font_scale 1.5");Thread.sleep(1000)}
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            scenario.onActivity {activity -> activity.setContent {
                MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF8FC2FF),background=Color(0xFF10151C),surface=Color(0xFF1A222E),onSurface=Color(0xFFF0F6FC),onSurfaceVariant=Color(0xFFB3C6D5))) {
                    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                            if(preferences) com.vlesscardvpn.ui.TorCountryPreferences(com.vlesscardvpn.model.Settings(torExitCountries="US,DE")) {}
                            else com.vlesscardvpn.ui.TorExitCountryCard(TorExitStatus.Info(true,country))
                        }
                    }
                }
            }}
            capture(name,if(preferences) "Сохранить страны" else if(country==null) "не определена" else "Германия")
        }
        if(large) {shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")}
    }
    @Test fun captureRealDashboardStatesWithoutConnectingToVpn() {
        try {
            torView("tor-country");torView("tor-country-unknown",country=null);torView("tor-country-large",large=true)
            torView("tor-preferences",preferences=true);torView("tor-preferences-large",preferences=true,large=true)
            manualProgress("manual-progress");manualProgress("manual-progress-large",large=true)
            show("idle-dark");show("idle-light",theme="light");show("searching",state=Tunnel.State.CONNECTING)
            show("wait-offline",state=Tunnel.State.CONNECTING,wait=AutoSearchCampaign.WaitState(8,AutoSearchCampaign.WaitReason.OFFLINE,2000))
            show("wait-catalog",state=Tunnel.State.CONNECTING,wait=AutoSearchCampaign.WaitState(8,AutoSearchCampaign.WaitReason.CATALOG_RETRY,5000))
            show("wait-backoff",state=Tunnel.State.CONNECTING,wait=AutoSearchCampaign.WaitState(8,AutoSearchCampaign.WaitReason.BACKOFF,5000))
            show("network-recovery",state=Tunnel.State.CONNECTED)
            show("background-running",state=Tunnel.State.CONNECTED,background=BackgroundOptimization.Stage.RUNNING)
            show("background-power",state=Tunnel.State.CONNECTED,background=BackgroundOptimization.Stage.POWER)
            show("background-deferred",state=Tunnel.State.CONNECTED,background=BackgroundOptimization.Stage.DEFERRED)
            show("background-finished",state=Tunnel.State.CONNECTED,background=BackgroundOptimization.Stage.FINISHED)
            BackgroundOptimization.clear()
            show("error",state=Tunnel.State.ERROR);show("partial",state=Tunnel.State.CONNECTED,ok=false)
            show("connected",state=Tunnel.State.CONNECTED,ok=true);show("large-narrow",large=true)
        } finally {
            Tunnel.status.value=Tunnel.Status();ConnectionPassport.clear();Actions.progress.value=Actions.Progress()
            shell("settings put system font_scale 1.0");shell("wm size reset");shell("wm density reset")
        }
    }
}
