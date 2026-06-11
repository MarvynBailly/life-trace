package com.lifetrace

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * All telemetry collection + HTTP to the Pi ingest API.
 * Every network call here is blocking; callers must run them off the main thread.
 */
object Net {

    private const val DEVICE = "pixel-10a"

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return fmt.format(System.currentTimeMillis())
    }

    private fun todayLocal(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(System.currentTimeMillis())
    }

    // ---- HTTP helpers ----

    private fun post(path: String, body: JSONObject): String {
        val url = URL(Config.baseUrl + path)
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer " + Config.token)
        OutputStreamWriter(c.outputStream).use { it.write(body.toString()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: $resp")
        return resp
    }

    private fun get(path: String, auth: Boolean): String {
        val url = URL(Config.baseUrl + path)
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 8000
        c.readTimeout = 8000
        if (auth) c.setRequestProperty("Authorization", "Bearer " + Config.token)
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: $resp")
        return resp
    }

    // ---- collection ----

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Per-app foreground seconds + open counts for today, via usage events. */
    private fun appUsageToday(ctx: Context): JSONArray {
        val out = JSONArray()
        if (!hasUsageAccess(ctx)) return out
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = System.currentTimeMillis()

        val fgSeconds = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val lastFg = HashMap<String, Long>()

        val events = usm.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    lastFg[pkg] = e.timeStamp
                    opens[pkg] = (opens[pkg] ?: 0) + 1
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val s = lastFg.remove(pkg)
                    if (s != null) {
                        fgSeconds[pkg] = (fgSeconds[pkg] ?: 0) + (e.timeStamp - s) / 1000
                    }
                }
            }
        }
        // still-foreground app at query time
        for ((pkg, s) in lastFg) {
            fgSeconds[pkg] = (fgSeconds[pkg] ?: 0) + (end - s) / 1000
        }

        val pm = ctx.packageManager
        val date = todayLocal()
        for ((pkg, secs) in fgSeconds) {
            if (secs < 5) continue
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (ex: Exception) { pkg }
            val row = JSONObject()
            row.put("date", date)
            row.put("package", pkg)
            row.put("app_label", label)
            row.put("foreground_seconds", secs)
            row.put("opens", opens[pkg] ?: 0)
            out.put(row)
        }
        return out
    }

    private fun location(ctx: Context): JSONArray {
        val out = JSONArray()
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: android.location.Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                val l = try { lm.getLastKnownLocation(p) } catch (se: SecurityException) { null } ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
            best?.let {
                val o = JSONObject()
                o.put("lat", it.latitude); o.put("lon", it.longitude)
                o.put("accuracy", it.accuracy.toDouble()); o.put("provider", it.provider)
                out.put(o)
            }
        } catch (ex: Exception) { /* no location permission yet */ }
        return out
    }

    private fun network(ctx: Context): JSONArray {
        val out = JSONArray()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetworkInfo
            val o = JSONObject()
            var type = "none"; var ssid: String? = null
            if (active != null && active.isConnected) {
                type = if (active.type == ConnectivityManager.TYPE_WIFI) "wifi" else "cell"
                if (type == "wifi") {
                    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    ssid = wm.connectionInfo?.ssid?.replace("\"", "")
                }
            }
            o.put("type", type)
            if (ssid != null) o.put("ssid", ssid)
            out.put(o)
        } catch (ex: Exception) { }
        return out
    }

    private fun status(ctx: Context): JSONArray {
        val out = JSONArray()
        try {
            val bIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (bIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val o = JSONObject()
            o.put("battery", pct)
            o.put("charging", plugged)
            out.put(o)
        } catch (ex: Exception) { }
        return out
    }

    /** Build the batch, POST it, return a short human status string. */
    fun collectAndUpload(ctx: Context): String {
        val batch = JSONObject()
        batch.put("device", DEVICE)
        val meta = JSONObject(); meta.put("model", android.os.Build.MODEL); batch.put("meta", meta)
        val apps = appUsageToday(ctx)
        batch.put("app_usage", apps)
        batch.put("locations", location(ctx))
        batch.put("networks", network(ctx))
        batch.put("status", status(ctx))
        val resp = post("/ingest/telemetry", batch)
        var totalMin = 0L
        for (i in 0 until apps.length()) totalMin += apps.getJSONObject(i).getLong("foreground_seconds")
        return "uploaded ${apps.length()} apps (${totalMin / 60} min screen) at ${isoNow().substring(11,16)}"
    }

    // ---- check-in ----

    fun fetchCheckin(): JSONObject = JSONObject(get("/checkin/today", false))

    fun submitCheckin(answers: JSONObject, mood: Int?, energy: Int?, focus: Int?): String {
        val body = JSONObject()
        body.put("answers", answers)
        if (mood != null) body.put("mood", mood)
        if (energy != null) body.put("energy", energy)
        if (focus != null) body.put("focus", focus)
        return post("/checkin/answer", body)
    }

    fun fetchInsights(): JSONArray = JSONObject(get("/insights/latest", true)).getJSONArray("insights")
}
