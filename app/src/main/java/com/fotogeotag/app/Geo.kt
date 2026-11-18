package com.fotogeotag.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.location.Geocoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

object Geo {

    data class Place(val title: String, val fullAddress: String)

    /** Alamat dari koordinat (butuh internet). Harus dipanggil di background thread. */
    @Suppress("DEPRECATION")
    fun reverse(context: Context, lat: Double, lng: Double): Place? {
        if (!Geocoder.isPresent()) return null
        return try {
            val list = Geocoder(context, Locale("id", "ID")).getFromLocation(lat, lng, 1)
            val a = list?.firstOrNull() ?: return null
            val area = a.locality ?: a.subAdminArea ?: a.subLocality
            val parts = listOfNotNull(area, a.adminArea, a.countryName).distinct()
            val flag = a.countryCode?.let { flagEmoji(it) } ?: ""
            val title = parts.joinToString(", ") + if (flag.isNotEmpty()) " $flag" else ""
            Place(title, a.getAddressLine(0) ?: "")
        } catch (e: Exception) {
            null
        }
    }

    private fun flagEmoji(code: String): String {
        val cc = code.uppercase(Locale.US)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        return cc.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** Potongan peta satelit berpusat di koordinat (tile Esri World Imagery). Background thread. */
    fun mapImage(lat: Double, lng: Double, zoom: Int = 17, size: Int = 512): Bitmap? {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val px = (lng + 180.0) / 360.0 * n * 256
        val py = (1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n * 256
        val left = px - size / 2.0
        val top = py - size / 2.0

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(140, 150, 140))
        var any = false
        for (ty in floor(top / 256).toInt()..floor((top + size - 1) / 256).toInt()) {
            if (ty < 0 || ty >= n) continue
            for (tx in floor(left / 256).toInt()..floor((left + size - 1) / 256).toInt()) {
                val wx = ((tx % n) + n) % n
                val tile = download(
                    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$ty/$wx"
                ) ?: continue
                canvas.drawBitmap(tile, (tx * 256 - left).toFloat(), (ty * 256 - top).toFloat(), null)
                tile.recycle()
                any = true
            }
        }
        return if (any) out else null
    }

    private fun download(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "FotoGeotag/1.0 (Android)")
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
