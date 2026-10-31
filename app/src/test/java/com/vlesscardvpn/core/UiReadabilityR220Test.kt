package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.pow

/** Source wiring and palette regression; NOT an Android screenshot/instrumentation test. */
class UiReadabilityR220Test {
    private fun source(name:String):String {
        val path="src/main/java/com/vlesscardvpn/ui/$name.kt"
        return listOf(File(path),File("app/$path")).first {it.isFile}.readText()
    }
    private fun lum(rgb:Long):Double {
        fun channel(shift:Int):Double {
            val x=((rgb shr shift) and 255)/255.0
            return if(x<=0.04045) x/12.92 else ((x+0.055)/1.055).pow(2.4)
        }
        return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
    }
    private fun contrast(a:Long,b:Long):Double {
        val x=lum(a);val y=lum(b)
        return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }
    @Test fun transparentScaffoldSpecifiesForeground() {
        val s=source("AppUi")
        assertTrue(s.contains("containerColor = if (sea) Color.Transparent else scheme.background,"))
        assertTrue(s.contains("contentColor = scheme.onBackground,"))
    }
    @Test fun darkOceanTextHasAccessibleContrast() {
        for(bg in listOf(0x081323L,0x10243AL,0x0A3440L,0x10151CL,0x1A222EL))
            assertTrue("background $bg",contrast(0xF0F6FCL,bg)>=4.5)
        assertTrue(contrast(0x172334L,0xF3F6FBL)>=4.5)
    }
    @Test fun chatPanelsUseThemeColorsInsteadOfFixedDarkSurfaces() {
        val s=source("ChatScreen")
        assertFalse(s.contains("Color(0xFF12151B)"));assertFalse(s.contains("Color(0xFF1F232C)"))
        assertTrue(s.contains("private val Bubble: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant"))
        assertTrue(s.contains("fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant"))
    }
    @Test fun messageForegroundMatchesItsThemeContainer() {
        val s=source("ChatScreen")
        assertTrue(s.contains("MaterialTheme.colorScheme.primaryContainer"))
        assertTrue(s.contains("color = if (m.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant"))
    }
}
