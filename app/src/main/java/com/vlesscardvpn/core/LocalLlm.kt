package com.vlesscardvpn.core

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * A real language model on the phone for «Помощник» (Google MediaPipe LLM Inference, CPU/GPU, no internet needed
 * after the download). Models are downloaded on demand from Hugging Face (litert-community, int8): through the running
 * VPN when connected (Hugging Face is often blocked), resumable.
 */
object LocalLlm {
    data class Model(val id: String, val title: String, val note: String, val url: String, val size: Long, val ramGb: Int, val maxTokens: Int, val thinks: Boolean = false)

    private const val HF = "https://huggingface.co/litert-community/"
    val MODELS = listOf(
        Model("qwen15", "Qwen 2.5 1.5B", "рекомендуется: лучше всех по-русски, отвечает за 5–20 с", HF + "Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 1_597_913_616, 4, 1280),
        Model("deepseek", "DeepSeek R1 1.5B (сжатый)", "сначала «думает», потом отвечает — медленнее, по-русски слабее Qwen", HF + "DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.task", 1_834_078_546, 6, 4096, thinks = true),
        Model("qwen05", "Qwen 2.5 0.5B (лёгкая)", "для слабых телефонов: быстро, но проще и чаще ошибается", HF + "Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 546_660_344, 3, 1280),
    )
    fun model(id: String) = MODELS.firstOrNull { it.id == id }

    data class Dl(val running: Boolean = false, val id: String = "", val done: Long = 0, val total: Long = 0, val error: String = "")
    val download = MutableStateFlow(Dl())

    private fun dir(c: Context) = File(c.filesDir, "llm").apply { mkdirs() }
    fun file(c: Context, m: Model) = File(dir(c), m.id + ".task")
    fun installed(c: Context, m: Model) = file(c, m).let { it.exists() && it.length() == m.size }
    fun installedAny(c: Context) = MODELS.filter { installed(c, it) }
    fun ramGb(c: Context): Double = runCatching {
        val mi = android.app.ActivityManager.MemoryInfo(); c.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi); mi.totalMem / 1e9
    }.getOrDefault(0.0)

    @Volatile private var cancelDl = false
    fun cancel() { cancelDl = true }

    /** Blocking (call on IO). Resumes a .part file; returns "" or an error text. */
    fun fetch(c: Context, m: Model): String {
        val f = file(c, m); val part = File(f.path + ".part")
        if (installed(c, m)) return ""
        if (dir(c).usableSpace + part.length() < m.size + 200_000_000) return "Мало места: нужно ${"%.1f".format(m.size / 1e9)} ГБ свободной памяти"
        cancelDl = false
        val b = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true)
        // the app itself is outside the VPN: go through its own SOCKS when connected (Hugging Face is blocked for many)
        Tunnel.socks?.let { b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it.port))) }
        val client = b.build()
        var attempt = 0
        while (true) {
            val have = part.length()
            download.value = Dl(true, m.id, have, m.size)
            val r = runCatching {
                client.newCall(Request.Builder().url(m.url).header("Range", "bytes=$have-").build()).execute().use { resp ->
                    if (resp.code == 416) return@use
                    if (!resp.isSuccessful) error("сервер ответил ${resp.code}")
                    val append = resp.code == 206
                    java.io.FileOutputStream(part, append).use { out ->
                        val src = resp.body!!.byteStream(); val buf = ByteArray(256 * 1024); var done = if (append) have else 0L; var last = 0L
                        while (true) {
                            if (cancelDl) error("остановлено")
                            val n = src.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - last > 4_000_000) { last = done; download.value = Dl(true, m.id, done, m.size) }
                        }
                    }
                }
            }
            if (part.length() >= m.size) break
            val err = r.exceptionOrNull()?.message ?: "обрыв"
            if (cancelDl || ++attempt >= 5) { download.value = Dl(false, m.id, part.length(), m.size, err); return "Загрузка прервана: $err. Нажмите ещё раз — продолжу с ${part.length() * 100 / m.size}%" }
            Thread.sleep(2000)
        }
        if (part.length() != m.size) { part.delete(); download.value = Dl(false, m.id, 0, m.size, "файл повреждён"); return "Файл повреждён — скачайте заново" }
        part.renameTo(f)
        download.value = Dl(false, m.id, m.size, m.size)
        return ""
    }

    fun delete(c: Context, m: Model) { close(); file(c, m).delete(); File(file(c, m).path + ".part").delete() }

    @Volatile private var engine: LlmInference? = null
    @Volatile private var engineId = ""
    @Synchronized fun close() { runCatching { engine?.close() }; engine = null; engineId = "" }

    @Synchronized private fun engine(c: Context, m: Model): LlmInference {
        engine?.takeIf { engineId == m.id }?.let { return it }
        close()
        val opts = LlmInference.LlmInferenceOptions.builder().setModelPath(file(c, m).path).setMaxTokens(m.maxTokens).setMaxTopK(40).build()
        return LlmInference.createFromOptions(c, opts).also { engine = it; engineId = m.id }
    }

    /** Hides the model's «thinking» (DeepSeek R1: <think>…</think>) — shows only the answer, or a hint while it thinks. */
    fun visible(raw: String): String {
        val t = raw.replace("<｜end▁of▁sentence｜>", "").replace("<|im_end|>", "")
        val end = t.indexOf("</think>")
        return when {
            end >= 0 -> t.substring(end + 8).trim()
            t.trimStart().startsWith("<think>") -> "🤔 думаю…"
            else -> t.trim()
        }
    }

    /** Streams the answer; [onPart] gets the visible text so far. Blocking until done. */
    suspend fun ask(c: Context, m: Model, prompt: String, onPart: (String) -> Unit): String {
        val e = engine(c, m)
        val sb = StringBuilder()
        return suspendCancellableCoroutine { cont ->
            val fut = e.generateResponseAsync(prompt) { part, done ->
                synchronized(sb) { sb.append(part ?: "") }
                val v = visible(synchronized(sb) { sb.toString() })
                onPart(v)
                if (done && cont.isActive) cont.resume(v)
            }
            fut.addListener({ val ex = runCatching { fut.get() }.exceptionOrNull(); if (ex != null && cont.isActive) cont.resume(visible(sb.toString()).ifEmpty { "Ошибка модели: ${ex.cause?.message ?: ex.message}" }) }, Runnable::run)
        }
    }

    /** The prompt: role + rules + the app's real measurements (incl. the rule-based answer) + the last turns. */
    fun prompt(question: String, facts: String, hint: String, history: List<Pair<Boolean, String>>): String = buildString {
        append("Ты — Помощник VPN-приложения VLESS Card на телефоне пользователя в России. Отвечай только по-русски, коротко (до 6 предложений), ")
        append("дружелюбно и по делу. Опирайся на ДАННЫЕ, цифры не выдумывай. Если нужно действие, посоветуй кнопку: «Подобрать маскировку», ")
        append("«Проверить серверы», «Измерить скорость» или режим «Авто».\n\nДАННЫЕ:\n").append(facts.take(1600))
        if (hint.isNotBlank()) append("\nПодсказка по замерам: ").append(hint.take(700))
        val h = history.takeLast(4).joinToString("\n") { (mine, t) -> (if (mine) "Пользователь: " else "Помощник: ") + t.take(300) }
        if (h.isNotBlank()) append("\n\nНЕДАВНИЙ ДИАЛОГ:\n").append(h)
        append("\n\nВопрос пользователя: ").append(question.take(500))
    }
}
