package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Main toggle double tap, accessible settings rows (source guard, not a device test) and mask evidence memory. */
class ButtonsMasksR246Test {
    @Test fun secondHalfOfDoubleTapIsSwallowed() {
        val g = TapGuard()
        assertTrue(g.accept(1_000))
        assertFalse(g.accept(1_200))   // «Подключить» → «Отменить» flip must not cancel the start
        assertFalse(g.accept(1_699))
        assertTrue(g.accept(1_700))    // a deliberate tap passes
        assertTrue(g.accept(5_000))
    }

    @Test fun clockGoingBackNeverLocksTheButton() {
        val g = TapGuard()
        assertTrue(g.accept(10_000))
        assertTrue(g.accept(2_000))
        assertFalse(g.accept(2_100))
    }

    @Test fun maskMemorySlidesInsteadOfGrowingForever() {
        var s = MaskStat()
        repeat(MaskStat.WINDOW) { s = s.record(false) }
        assertEquals(MaskStat(0, MaskStat.WINDOW), s)
        s = s.record(true)
        assertTrue(s.ok + s.fail <= MaskStat.WINDOW)
        assertEquals(1, s.ok) // the newest success survives halving
        var t = MaskStat(200, 3)
        t = t.record(false)
        assertTrue(t.ok + t.fail < 203)
    }

    @Test fun oldSuccessDoesNotKeepFailingMaskProven() {
        assertEquals(0, MaskStat(1, 0).bucket)
        assertEquals(0, MaskStat(1, 3).bucket)
        assertEquals(1, MaskStat(1, 4).bucket)  // mixed: tried after proven ones
        assertEquals(2, MaskStat(1, 5).bucket)  // keeps failing here
        assertEquals(2, MaskStat(0, 4).bucket)
        assertEquals(1, MaskStat(0, 3).bucket)
        assertEquals(1, MaskStat().bucket)
        assertEquals(0, MaskStat(10, 30).bucket)
        assertEquals(2, MaskStat(1, 30).bucket)
    }

    @Test fun recordedFailuresDemoteAProvenMask() {
        var s = MaskStat(1, 0)
        repeat(5) { s = s.record(false) }
        assertEquals(2, s.bucket)
        repeat(4) { s = s.record(true) }
        assertEquals(0, s.bucket) // and recovers when it passes again
    }

    private fun ui(name: String): String {
        val p = "src/main/java/com/vlesscardvpn/ui/$name"
        return listOf(File(p), File("app/$p")).first { it.isFile }.readText()
    }

    @Test fun checkboxRowsAreSingleToggleTargets() {
        val dir = listOf(File("src/main/java/com/vlesscardvpn/ui"), File("app/src/main/java/com/vlesscardvpn/ui")).first { it.isDirectory }
        dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "Rows.kt" }!!.forEach { f ->
            val lines = f.readLines()
            lines.forEachIndexed { i, line ->
                if (".clickable" in line) {
                    val next = lines.subList(i, minOf(lines.size, i + 4)).joinToString("\n")
                    assertFalse("${f.name}:${i + 1} clickable row with its own checkbox/radio", Regex("(Checkbox|RadioButton)\\((?![^)]*null)").containsMatchIn(next))
                }
            }
        }
        val rows = ui("Rows.kt")
        assertTrue(rows.contains("role = Role.Checkbox")); assertTrue(rows.contains("role = Role.Switch")); assertTrue(rows.contains("role = Role.RadioButton"))
        assertTrue(rows.contains("onCheckedChange = null"))
    }

    @Test fun settingsTogglesAreWholeRows() {
        assertTrue(ui("SettingsScreen.kt").contains("SwitchRow(v, on)"))
        assertTrue(ui("TorSettingsCard.kt").contains("SwitchRow(value, onChange"))
        assertTrue(ui("ExperienceSettings.kt").contains("SwitchRow(checked,onChange"))
        val home = ui("HomeScreen.kt")
        assertTrue(home.contains("tapGuard.accept("))
        assertTrue(home.contains("stateDescription=when(action)"))
        assertTrue(home.contains("onClickLabel = \"Открыть серверы\""))
        assertTrue(ui("ChatScreen.kt").contains("contentDescription = \"Отправить сообщение\""))
    }
}
