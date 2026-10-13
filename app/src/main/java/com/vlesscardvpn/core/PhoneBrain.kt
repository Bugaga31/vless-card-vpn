package com.vlesscardvpn.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Separate from mask learning. Retains only model weights/counters, encrypted; raw readings stay in RAM. */
object PhoneBrain {
    val status = MutableStateFlow("Локальное обучение: собираем опыт")
    private var model = PhoneLearning()
    private var loaded = false
    private var storageOk = true
    private var saveFailed = false
    private var last = -60000L
    private var previousTemp = 0.0
    private var pending: DoubleArray? = null
    private var pendingAt = 0L
    private var enabled = false
    @Synchronized fun reset(ctx: Context) {
        model = PhoneLearning(); pending = null; previousTemp = 0.0; last = -60000
        LocalSecrets.deletePhoneModel(ctx); loaded = true; storageOk = true; saveFailed = false; status.value = "Опыт модели удалён"
    }
    @Synchronized fun update(ctx: Context, i: DeviceSignals, on: Boolean): Boolean {
        if (!on) {
            if (enabled) reset(ctx)
            enabled = false; status.value = "Локальное обучение выключено"; return false
        }
        if (!loaded) { storageOk = runCatching { LocalSecrets.readPhoneModel(ctx)?.let { model.restore(it) } }.isSuccess; loaded = true }
        if (!storageOk) { status.value = "Не удалось открыть модель: данные сохранены, обучение отключено до сброса опыта"; return false }
        enabled = true
        if (i.temp <= 0 && i.thermal < 0 && i.memoryUsed < 0) { pending = null; status.value = "Обучение ждёт доступных тепловых датчиков"; return false }
        if (i.now - last > 180000) { pending = null; previousTemp = 0.0 }
        if (i.now - last >= 60000) {
            // Train only on a future label ~one minute later, never across sleep/process gaps.
            pending?.takeIf { i.now - pendingAt in 60000..180000 }?.let {
                model.train(it, i.temp >= 39 || i.thermal >= 2 || i.lowMemory)
                if (model.samples % 10 == 0) saveFailed = runCatching { LocalSecrets.writePhoneModel(ctx, model.encode()) }.isFailure
            }
            val temp = if (i.temp > 0) i.temp else 30.0
            pending = PhoneLearning.features(i.copy(temp = temp), previousTemp.takeIf { it > 0 } ?: temp)
            previousTemp = temp; pendingAt = i.now; last = i.now
        }
        val risk = pending?.let { model.predict(it) } ?: 0.0
        val reduce = model.ready() && risk >= .8
        status.value = "Отдельная нейросеть: ${model.samples} примеров · " +
            if (!model.ready()) "обучается; пока используются только показания датчиков" else if (reduce) "прогноз нагрева/нехватки памяти: советуем проверить приложения" else "повышенный риск не обнаружен"
        if (saveFailed) status.value += " · опыт пока не удалось сохранить"
        return reduce
    }
}
