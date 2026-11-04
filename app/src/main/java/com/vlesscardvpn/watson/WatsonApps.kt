package com.vlesscardvpn.watson

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object WatsonApps {
    data class Snapshot(val apps:List<WatsonApp>,val incomplete:Boolean,val at:Long)
    /** Explicit user action only. Launcher-visible packages plus self, not all installed apps. */
    @Suppress("DEPRECATION")
    suspend fun scan(ctx:Context):Snapshot {
        val pm=ctx.packageManager
        val launchable=pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        val names=(launchable.map{it.activityInfo.packageName}+ctx.packageName).distinct().sorted()
        val chosen=names.take(300)
        val apps=chosen.map {pkg->
            currentCoroutineContext().ensureActive()
            try {
                val info=pm.getPackageInfo(pkg,PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)
                val requested=info.requestedPermissions?.toSet().orEmpty()
                val granted=info.requestedPermissions.orEmpty().mapIndexedNotNull {i,p->
                    if(((info.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0) p else null}.toSet()
                val services=info.services.orEmpty()
                WatsonApp(pkg,info.applicationInfo?.loadLabel(pm)?.toString()?.take(120) ?: pkg,requested,granted,
                    services.any{it.permission=="android.permission.BIND_ACCESSIBILITY_SERVICE"},
                    services.any{it.permission=="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"},
                    services.any{it.permission=="android.permission.BIND_VPN_SERVICE"},
                    info.activities.orEmpty().count{it.exported}+info.receivers.orEmpty().count{it.exported}+
                        info.providers.orEmpty().count{it.exported}+services.count{it.exported},
                    ((info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            } catch(_:PackageManager.NameNotFoundException) {WatsonApp(pkg,pkg,emptySet(),emptySet(),error=true)}
              catch(_:SecurityException) {WatsonApp(pkg,pkg,emptySet(),emptySet(),error=true)}
        }
        return Snapshot(apps.sortedBy{it.label.lowercase()},names.size>300 || apps.any{it.error},System.currentTimeMillis())
    }
}
