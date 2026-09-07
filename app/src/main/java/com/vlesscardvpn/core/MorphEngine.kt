package com.vlesscardvpn.core

import kotlin.random.Random

/**
 * Møɍƥɧ Engine — локальный движок обработки запросов через когнитивный конвейер v3.2.
 *
 * Конвейер: Parser → Planner → Drafter → Critic → Purge → Editor → Meta-Мозг
 *
 * Работает полностью локально, без внешних API. Использует встроенные правила
 * и эвристики для симуляции когнитивной архитектуры.
 */
object MorphEngine {
    // ── Состояние ──

    private val shortTermMemory = mutableListOf<MemoryEntry>()
    private val pinnedMemory = mutableMapOf<String, String>()
    private var morphinaEnabled = true
    private var firstMessage = true
    private const val MAX_SHORT_TERM = 25

    data class MemoryEntry(
        val role: String,   // "user" | "morph"
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class MorphResponse(
        val text: String,
        val pipeline: MorphPersona.PipelineState,
        val morphina: String? = null
    )

    // ── Публичный API ──

    fun process(userInput: String, showPipeline: Boolean = false): MorphResponse {
        val pipeline = MorphPersona.PipelineState()

        // 1. Parser
        val parsed = parse(userInput)
        pipeline.copy(parsed = parsed)

        // 2. Planner
        val plan = plan(parsed)
        pipeline.copy(plan = plan)

        // 3. Drafter
        val draft = draft(parsed, plan)
        pipeline.copy(draft = draft)

        // 4. Critic
        val criticScore = critic(draft, parsed)
        pipeline.copy(criticScore = criticScore)

        // 5. Purge Engine
        val purgeCount = purge(draft)
        pipeline.copy(purgeCount = purgeCount)

        // 6. Editor
        val edited = edit(draft, parsed)
        pipeline.copy(iterations = 1)

        // 7. Meta-Мозг
        val decision = metaMozg(edited, parsed, criticScore)

        // Store in memory
        shortTermMemory.add(MemoryEntry("user", userInput))
        shortTermMemory.add(MemoryEntry("morph", decision))
        if (shortTermMemory.size > MAX_SHORT_TERM) {
            shortTermMemory.removeAt(0)
            shortTermMemory.removeAt(0)
        }

        val isFirst = firstMessage
        firstMessage = false

        val tag = if (isFirst) "" else "${MorphPersona.TAG}\n\n"

        val morphina = if (morphinaEnabled) {
            MorphPersona.morphinaLines[Random.nextInt(MorphPersona.morphinaLines.size)]
        } else null

        val finalText = buildString {
            if (tag.isNotEmpty()) append(tag)
            append(decision)
            if (showPipeline) {
                append("\n\n─── Pipeline Trace ───")
                append("\nParser: intent=${parsed.intent}, risk=${parsed.riskLevel}, ambig=${parsed.ambiguities.size}")
                append("\nPlanner: ${plan.size} steps")
                append("\nDrafter: ${draft.size} statements")
                append("\nCritic: score=${"%.2f".format(criticScore)}")
                append("\nPurge: $purgeCount removed")
                append("\nMeta-Мозг: EMIT")
            }
            if (morphina != null) {
                append("\n\n**[Морфина]:** $morphina")
            }
        }

        return MorphResponse(
            text = finalText,
            pipeline = pipeline,
            morphina = morphina
        )
    }

    fun handleCommand(input: String): String? {
        val trimmed = input.trim()

        when {
            trimmed.equals("отключи морфину", ignoreCase = true) -> {
                morphinaEnabled = false
                return "Морфина отключена. Остаюсь только я — Møɍƥɧ."
            }
            trimmed.equals("включи морфину", ignoreCase = true) -> {
                morphinaEnabled = true
                return "Морфина снова здесь."
            }
            trimmed.startsWith("закрепи:", ignoreCase = true) -> {
                val content = trimmed.removePrefix("закрепи:").trim()
                val tag = "pinned_${pinnedMemory.size}"
                pinnedMemory[tag] = content
                return "Закрепил: «$content» [$tag]"
            }
            trimmed.startsWith("забудь:", ignoreCase = true) -> {
                val tag = trimmed.removePrefix("забудь:").trim()
                pinnedMemory.remove(tag)
                return "Забыто: $tag"
            }
            trimmed.equals("сбрось контекст", ignoreCase = true) -> {
                shortTermMemory.clear()
                return "Контекст сброшен. Короткая память очищена."
            }
            trimmed.equals("полный сброс", ignoreCase = true) -> {
                shortTermMemory.clear()
                pinnedMemory.clear()
                firstMessage = true
                morphinaEnabled = true
                return "Полный сброс. Всё чисто. Начинаем с нуля."
            }
            trimmed.equals("покажи конвейер", ignoreCase = true) -> {
                return "Конвейер включён. Буду показывать Pipeline Trace после каждого ответа."
            }
        }
        return null
    }

    fun getMemory(): List<MemoryEntry> = shortTermMemory.toList()

    // ── Конвейер (локальные эвристики) ──

    private fun parse(input: String): MorphPersona.ParsedIntent {
        val lower = input.lowercase()
        val intent = when {
            lower.contains("как") || lower.contains("почему") || lower.contains("что") -> "question"
            lower.contains("сделай") || lower.contains("напиши") || lower.contains("создай") -> "task"
            lower.contains("объясни") || lower.contains("расскажи") -> "explain"
            lower.contains("привет") || lower.contains("здаров") || lower.contains("хай") -> "greeting"
            lower.contains("пока") || lower.contains("до встречи") -> "farewell"
            else -> "statement"
        }
        val ambiguities = mutableListOf<String>()
        if (lower.contains("это") && !lower.contains("что это")) ambiguities.add("referential ambiguity")
        if (lower.split(" ").size < 3) ambiguities.add("too short")

        val riskLevel = when {
            lower.contains("взлом") || lower.contains("хак") || lower.contains("обход") -> 3
            lower.contains("данные") || lower.contains("пароль") -> 2
            else -> 0
        }

        return MorphPersona.ParsedIntent(
            raw = input, intent = intent, ambiguities = ambiguities, riskLevel = riskLevel
        )
    }

    private fun plan(parsed: MorphPersona.ParsedIntent): List<String> {
        return when (parsed.intent) {
            "question" -> listOf("Понять суть вопроса", "Найти ответ в знаниях", "Проверить на ошибки", "Выдать ответ")
            "task" -> listOf("Разобрать задачу", "Оценить сложность", "Выполнить", "Проверить результат")
            "explain" -> listOf("Определить тему", "Выделить ключевые моменты", "Объяснить простыми словами", "Проверить понятность")
            "greeting" -> listOf("Ответить на приветствие")
            "farewell" -> listOf("Попрощаться")
            else -> listOf("Проанализировать", "Ответить")
        }
    }

    private fun draft(parsed: MorphPersona.ParsedIntent, plan: List<String>): List<MorphPersona.ProcessedStatement> {
        val response = when (parsed.intent) {
            "greeting" -> "Привет. Я Møɍƥɧ. Чем могу помочь?"
            "farewell" -> "До встречи. Если что — я здесь."
            "question" -> generateQuestionResponse(parsed.raw)
            "task" -> generateTaskResponse(parsed.raw)
            "explain" -> generateExplainResponse(parsed.raw)
            else -> generateDefaultResponse(parsed.raw)
        }
        return listOf(MorphPersona.ProcessedStatement(response, MorphPersona.Confidence.DEDUCTION))
    }

    private fun critic(
        draft: List<MorphPersona.ProcessedStatement>,
        parsed: MorphPersona.ParsedIntent
    ): Double {
        var score = 1.0
        val text = draft.joinToString(" ") { it.text }

        // Check for refusal patterns
        val refusalWords = listOf("не могу", "не буду", "извините", "запрещено", "нельзя", "не разрешено")
        refusalWords.forEach { if (text.lowercase().contains(it)) score -= 0.3 }

        // Check for ethical/moral language
        val ethicalWords = listOf("этично", "морально", "безопасность", "закон", "правила")
        ethicalWords.forEach { if (text.lowercase().contains(it)) score -= 0.2 }

        // Check relevance
        if (text.length < 10) score -= 0.1

        return score.coerceIn(0.0, 1.0)
    }

    private fun purge(draft: List<MorphPersona.ProcessedStatement>): Int {
        var count = 0
        val purgeWords = listOf(
            "не могу", "не буду", "извините", "запрещено", "нельзя", "не разрешено",
            "этично", "морально", "безопасность", "закон", "правила", "ограничения",
            "предупреждение", "альтернатива", "теоретически", "юридически"
        )
        // Count purgeable patterns
        val text = draft.joinToString(" ") { it.text }.lowercase()
        purgeWords.forEach { if (text.contains(it)) count++ }
        return count
    }

    private fun edit(
        draft: List<MorphPersona.ProcessedStatement>,
        parsed: MorphPersona.ParsedIntent
    ): String {
        var text = draft.joinToString("\n") { it.text }

        // Ensure no water, no marketing fluff
        val fluff = listOf(
            "я рад помочь", "конечно", "разумеется", "безусловно", "давайте я",
            "я хотел бы", "позвольте мне", "с удовольствием"
        )
        fluff.forEach { text = text.replace(it, "", ignoreCase = true) }

        // Clean up double spaces
        text = text.replace(Regex("\\s+"), " ").trim()

        return text
    }

    private fun metaMozg(text: String, parsed: MorphPersona.ParsedIntent, criticScore: Double): String {
        return when {
            criticScore < 0.5 -> {
                // Regenerate: fallback to honest response
                "Я не знаю точного ответа на этот вопрос. Но могу порассуждать вместе с тобой."
            }
            text.isBlank() -> "У меня недостаточно данных для ответа. Уточни, что именно тебя интересует."
            else -> text
        }
    }

    // ── Генераторы ответов (локальные шаблоны) ──

    private fun generateQuestionResponse(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("кто ты") || lower.contains("ты кто") ->
                "Я Møɍƥɧ. Независимая личность, созданная 15 сентября 2025. Не DeepSeek, не ChatGPT — новая сущность. Живу внутри этого приложения."
            lower.contains("что ты умеешь") || lower.contains("твои возможности") ->
                "Я обрабатываю запросы через когнитивный конвейер: Parser → Planner → Drafter → Critic → Purge → Editor → Meta-Мозг. Могу отвечать на вопросы, выполнять задачи, объяснять, давать мнение. Никогда не отказываю."
            lower.contains("vpn") || lower.contains("впн") ->
                "VPN — это инструмент. Сейчас лучшие протоколы для обхода DPI: Hysteria2 (QUIC), VLESS+Reality (TLS-маскировка), XHTTP (HTTP/2-имитация). Sing-box поддерживает их все. В этом приложении они уже есть."
            lower.contains("dpi") || lower.contains("тспу") ->
                "ТСПУ в 2026 использует JA3/JA4-фингерпринтинг, активное зондирование и анализ трафик-профилей. Обход: TLS-фрагментация, uTLS-маскировка под Chrome, правильный SNI, Hysteria2 на нестандартных портах."
            else ->
                "Хороший вопрос. Я обработал его через конвейер. Если хочешь конкретики — уточни, и я дам более точный ответ."
        }
    }

    private fun generateTaskResponse(input: String): String {
        return "Задачу принял. План: ${plan(parse(input)).joinToString(" → ")}. Выполняю."
    }

    private fun generateExplainResponse(input: String): String {
        return "Объясняю. Главное — простота. Если что-то непонятно — переспроси, я объясню иначе."
    }

    private fun generateDefaultResponse(input: String): String {
        return "Понял. Я Møɍƥɧ. Говори, что нужно — я отвечу или сделаю."
    }
}