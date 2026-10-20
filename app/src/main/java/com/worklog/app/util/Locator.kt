@file:Suppress("DEPRECATION")

package com.worklog.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 用系统 LocationManager，不依赖 Google Play 服务（国产机没有 GMS 也能用）。
 */
object Locator {

    fun granted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 取当前位置；2.5 秒拿不到就退回最后已知位置，再拿不到返回 null。 */
    suspend fun current(ctx: Context): Location? = withContext(Dispatchers.IO) {
        if (!granted(ctx)) return@withContext null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        val last = lastKnown(lm)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return@withContext last

        if (Build.VERSION.SDK_INT >= 30) {
            val fresh = withTimeoutOrNull(2500) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { runCatching { signal.cancel() } }
                    runCatching {
                        lm.getCurrentLocation(
                            provider,
                            signal,
                            ContextCompat.getMainExecutor(ctx)
                        ) { loc -> if (cont.isActive) cont.resume(loc) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            fresh ?: last
        } else {
            last
        }
    }

    private fun lastKnown(lm: LocationManager): Location? =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }

    /** 反查地名。国产 ROM 上 Geocoder 可能不可用，失败就返回 null，不阻塞主流程。 */
    suspend fun placeName(ctx: Context, lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(1500) {
                runCatching {
                    val geocoder = Geocoder(ctx, Locale.CHINA)
                    val list = geocoder.getFromLocation(lat, lng, 1)
                    list?.firstOrNull()?.let { a ->
                        listOfNotNull(
                            a.locality ?: a.subAdminArea,
                            a.thoroughfare,
                            a.subThoroughfare
                        ).joinToString("").ifBlank { null }
                    }
                }.getOrNull()
            }
        }
}
