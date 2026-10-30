package com.vlesscardvpn.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Separate from mask learning. Retains only model weights/counters, encrypted; raw readings stay in RAM. */
object PhoneBrain {
    val status = MutableStateFlow("Локальное обучение: собираем опыт")
    private var model = PhoneLearning()
    private var fly = FlyPhoneLearning()
    private var loaded = false
    private var storageOk = true
    private var saveFailed = false
    private var last = -60000L
    private var previousTemp = 0.0
    private var pending: DoubleArray? = null
    private var pendingAt = 0L
    private var enabled = false
    @Synchronized fun reset(ctx: Context) {
        model = PhoneLearning(); fly = FlyPhoneLearning(); pending = null; previousTemp = 0.0; last = -60000
        LocalSecrets.deletePhoneModel(ctx); loaded = true; storageOk = true; saveFailed = false; status.value = "Опыт модели удалён"
    }
    @Synchronized fun update(ctx: Context, i: DeviceSignals, on: Boolean): Boolean {
        if (!on) {
            if (enabled) reset(ctx)
            enabled = false; status.value = "Локальное обучение выключено"; return false
        }
        if (!loaded) { storageOk = runCatching { LocalSecrets.readPhoneModel(ctx)?.let {text->
            if(text.startsWith("WATSON_FLY_1\n")) {
                val parts=text.split('\n');require(parts.size==3)
                model.restore(parts[1]);fly.restore(parts[2])
            } else model.restore(text) // Keep the previous encrypted model; start fly readout cold.
        } }.isSuccess; loaded = true }
        if (!storageOk) { status.value = "Не удалось открыть модель: данные сохранены, обучение отключено до сброса опыта"; return false }
        enabled = true
        if (i.temp <= 0 && i.thermal < 0 && i.memoryUsed < 0) { pending = null; status.value = "Обучение ждёт доступных тепловых датчиков"; return false }
        if (i.now - last > 180000) { pending = null; previousTemp = 0.0 }
        if (i.now - last >= 60000) {
            // Train only on a future label ~one minute later, never across sleep/process gaps.
            pending?.takeIf { i.now - pendingAt in 60000..180000 }?.let {
                val label=i.temp >= 39 || i.thermal >= 2 || i.lowMemory
                model.train(it,label)
                runCatching {fly.train(it,label)}
                if (model.samples % 10 == 0) saveFailed = runCatching { LocalSecrets.writePhoneModel(ctx, "WATSON_FLY_1\n"+model.encode()+"\n"+fly.encode()) }.isFailure
            }
            val temp = if (i.temp > 0) i.temp else 30.0
            pending = PhoneLearning.features(i.copy(temp = temp), previousTemp.takeIf { it > 0 } ?: temp)
            previousTemp = temp; pendingAt = i.now; last = i.now
        }
        val risk = pending?.let { model.predict(it) } ?: 0.0
        val flyRisk=pending?.let {runCatching {fly.predict(it)}.getOrNull()}
        val reduce = model.ready() && risk >= .8 || fly.ready() && flyRisk!=null && flyRisk>=.8
        status.value = "ВАТСОН FlyWire: ${fly.samples} будущих наблюдений · " +
            if (!model.ready() && !fly.ready()) "обучается; пока используются только показания датчиков" else if (reduce) "прогноз нагрева/нехватки памяти: советуем проверить приложения" else "повышенный риск не обнаружен"
        if (saveFailed) status.value += " · опыт пока не удалось сохранить"
        return reduce
    }
}
