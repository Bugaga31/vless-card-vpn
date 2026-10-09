package com.vlesscardvpn.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.vlesscardvpn.MainActivity
import com.vlesscardvpn.R
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.Tunnel

/** Home screen widget: one round button (connect / disconnect) + status and route. */
class VpnWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(ctx) }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != TOGGLE) return
        val s = Tunnel.status.value.state
        if (s == Tunnel.State.CONNECTED || s == Tunnel.State.CONNECTING) TunnelService.stop(ctx) else TunnelService.start(ctx)
        refresh(ctx)
    }

    companion object {
        const val TOGGLE = "com.vlesscardvpn.WIDGET_TOGGLE"

        /** Text and colour of the widget for a state (pure — tested). */
        fun look(state: Tunnel.State, checkOk: Boolean?, route: String, message: String): Triple<Int, String, String> = when (state) {
            Tunnel.State.CONNECTED -> if (checkOk == false) Triple(R.drawable.widget_btn_warn, "Подключено · нет интернета", "Нажмите в приложении «Починить»")
                else Triple(R.drawable.widget_btn_on, "Подключено", route.ifEmpty { "Нажмите, чтобы отключить" })
            Tunnel.State.CONNECTING -> Triple(R.drawable.widget_btn_busy, "Подключение…", message.ifEmpty { "Подбираю сервер и маску" })
            Tunnel.State.ERROR -> Triple(R.drawable.widget_btn_warn, "Ошибка", message.ifEmpty { "Нажмите, чтобы попробовать снова" })
            else -> Triple(R.drawable.widget_btn_off, "Выключено", "Нажмите, чтобы подключить")
        }

        fun refresh(ctx: Context) = runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, VpnWidget::class.java))
            if (ids.isEmpty()) return@runCatching
            val st = Tunnel.status.value
            val (bg, title, sub) = look(st.state, st.checkOk, st.route, st.message)
            val v = RemoteViews(ctx.packageName, R.layout.widget_vpn)
            v.setInt(R.id.w_btn, "setBackgroundResource", bg)
            v.setTextViewText(R.id.w_title, title)
            v.setTextViewText(R.id.w_sub, sub)
            val needsPermission = !Store.state.value.settings.proxyOnly && VpnService.prepare(ctx) != null
            val click = if (needsPermission) PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                else PendingIntent.getBroadcast(ctx, 1, Intent(ctx, VpnWidget::class.java).setAction(TOGGLE), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_btn, click)
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(ids, v)
        }
    }
}
