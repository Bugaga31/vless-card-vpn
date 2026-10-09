package com.vlesscardvpn

import com.vlesscardvpn.core.Assistant
import com.vlesscardvpn.core.LocalLlm
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.MaskBrain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R82Test {
    private val f = Assistant.Facts("Моб.: MTS", MaskBrain(), true, "Авто · 2 сервера", "Интернет работает", 10, 2,
        listOf(Triple("NL-1", 80, 12000)), Mode.AUTO, null, true, null)

    @Test fun hidesThinking() {
        assertEquals("🤔 думаю…", LocalLlm.visible("<think>\nПользователь спрашивает"))
        assertEquals("Ответ.", LocalLlm.visible("<think>ок</think>\n\nОтвет.<｜end▁of▁sentence｜>"))
        assertEquals("Привет", LocalLlm.visible(" Привет<|im_end|>"))
    }

    @Test fun offersModels() {
        val a = Assistant.answer("а можно засунуть дипсик?", f)
        assertEquals(LocalLlm.MODELS.size, a.buttons.count { it.cmd.startsWith("!dl ") })
        assertTrue(LocalLlm.MODELS.all { it.url.startsWith("https://huggingface.co/litert-community/") && it.url.endsWith(".task") })
    }

    @Test fun promptCarriesRealData() {
        val p = LocalLlm.prompt("почему медленно?", Assistant.factsText(f), "Лучший сервер: NL-1", listOf(true to "привет", false to "Привет!"))
        assertTrue(p.contains("NL-1") && p.contains("Моб.: MTS") && p.contains("почему медленно?") && p.contains("по-русски"))
        assertEquals("qwen15", Settings.fromJson(JSONObject(Settings(llmModel = "qwen15").toJson().toString())).llmModel)
    }
}
