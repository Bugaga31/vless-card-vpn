package com.vlesscardvpn

import com.vlesscardvpn.core.Assistant
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.MaskBrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class R81Test {
    private val net = "Моб.: MTS"
    private fun trained(): MaskBrain {
        val r = Random(5); val b = MaskBrain()
        val data = List(800) { i ->
            val m = Mask("t$i", "t", listOf("chrome", "firefox", "").random(r), packets = if (r.nextBoolean()) "tlshello" else "",
                length = if (r.nextBoolean()) "50-100" else "", mux = r.nextInt(3) == 0)
            MaskBrain.features(m, net, null) to (m.packets.isNotEmpty() && !m.mux)
        }
        data.chunked(40).forEach { b.learn(it) }
        return b
    }
    private fun facts(b: MaskBrain) = Assistant.Facts(net, b, true, "Авто · 2 сервера", "Интернет работает", 10, 2,
        listOf(Triple("NL-1", 80, 12000)), Mode.AUTO, null, true, null)

    @Test fun explainsWhatTheNetworkCuts() {
        val b = trained()
        val e = Assistant.effects(b, net)
        assertTrue(e.first().first.contains("дробление") || e.first().first.contains("ступеньки"))
        assertTrue(e.last().first.contains("mux"))
        val a = Assistant.answer("что режут в моей сети?", facts(b))
        assertTrue(a.text, a.text.contains("Помогает") && a.text.contains("дробление"))
        assertTrue(a.buttons.any { it.cmd == "!masks" })
    }

    @Test fun understandsQuestions() {
        val f = facts(MaskBrain())
        assertTrue(Assistant.answer("Почему так медленно???", f).buttons.any { it.cmd == "!speed" })
        assertEquals("!site rutracker.org", Assistant.answer("проверь rutracker.org", f).buttons.single().cmd)
        assertTrue(Assistant.answer("лучшие серверы", f).text.contains("NL-1"))
        assertTrue(Assistant.answer("сбрось обучение", f).buttons.any { it.cmd == "!reset" })
        assertTrue(Assistant.answer("какие маски лучше", f).text.contains("после первого подбора"))
        assertTrue(Assistant.answer("абракадабра", f).text.startsWith("Не понял"))
    }
}
