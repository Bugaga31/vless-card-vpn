package com.vlesscardvpn.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-persistent, endpoint-free UI feedback. This never initiates network work. */
object BackgroundOptimization {
 enum class Stage { SCHEDULED,RUNNING,POWER,TRAFFIC,HOLD,CHAIN,NETWORK,DEFERRED,APPLYING,FINISHED }
 private val mutable=MutableStateFlow("")
 val status=mutable.asStateFlow()
 fun message(stage:Stage):String=when(stage) {
  Stage.SCHEDULED->"Фон: сравнение запланировано после стабилизации соединения."
  Stage.RUNNING->"Фон: проверяем кандидатов и сравниваем скорость. Текущий маршрут остаётся подключён."
  Stage.POWER->"Фон: сравнение отложено — экран выключен или действует ограничение питания/нагрева."
  Stage.TRAFFIC->"Фон: сравнение отложено, чтобы не мешать активному трафику."
  Stage.HOLD->"Фон: необязательные проверки приостановлены пользователем."
  Stage.CHAIN->"Фон: обычное сравнение отложено для цепочки; у неё отдельные проверки."
  Stage.NETWORK->"Фон: ждём восстановления сети, старые сетевые результаты не применяем."
  Stage.DEFERRED->"Фон: применение нового маршрута отложено из-за активного трафика."
  Stage.APPLYING->"Фон: применяем обновлённый маршрут; текущие соединения могут перезапуститься."
  Stage.FINISHED->"Фон: проход завершён; следующий запланирован. Новый маршрут сейчас не применяется."
 }
 fun show(stage:Stage){mutable.value=message(stage)}
 fun clear(){mutable.value=""}
}

object BackgroundOptimizationPolicy {
 fun mayReplace(repairRequired:Boolean,qualityGain:Boolean)=repairRequired||qualityGain
 // Relative balance weights cannot improve a pool with only one active route.
 fun weightsNeedRefresh(routeCount:Int,old:String,new:String)=routeCount>1&&old!=new
}
