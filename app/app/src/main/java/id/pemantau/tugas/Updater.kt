package id.pemantau.tugas

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject

/** Update otomatis: cek rilis terbaru di GitHub, unduh APK, lalu pasang lewat PackageInstaller. */
object Updater {
    data class Info(val code: Long, val url: String, val notes: String)

    fun slug(c: Context): String = c.getString(R.string.repo_slug)

    @Suppress("DEPRECATION")
    fun current(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

    private fun fetch(c: Context): Info? {
        val s = slug(c); if (s.isBlank()) return null
        val r = JSONObject(Net.req("https://api.github.com/repos/$s/releases/latest", headers = mapOf("Accept" to "application/vnd.github+json")))
        val code = r.optString("tag_name").removePrefix("v").toLongOrNull() ?: return null
        val a = r.optJSONArray("assets") ?: return null
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (x.optString("name").endsWith(".apk")) return Info(code, x.optString("browser_download_url"), r.optString("body"))
        }
        return null
    }

    /** Cek rilis terbaru. Hasilnya disimpan agar banner muncul di aplikasi; notif hanya sekali per versi. */
    fun refresh(c: Context, notify: Boolean): Info? {
        val p = c.prefs()
        val i = try { fetch(c) } catch (e: Exception) { null } ?: return pending(c)
        if (i.code > current(c)) {
            p.edit().putLong("updCode", i.code).putString("updUrl", i.url).putString("updNotes", i.notes).apply()
            if (notify && p.getLong("updNotified", 0) < i.code) {
                pushNotif(c, "update", "Pembaruan tersedia", "Versi ${i.code} siap dipasang. Buka aplikasi lalu tekan Perbarui.")
                p.edit().putLong("updNotified", i.code).apply()
            }
            return i
        }
        p.edit().remove("updCode").remove("updUrl").remove("updNotes").apply()
        return null
    }

    fun pending(c: Context): Info? {
        val p = c.prefs(); val code = p.getLong("updCode", 0)
        return if (code > current(c)) Info(code, p.str("updUrl"), p.str("updNotes")) else null
    }

    /** Blocking: jalankan di thread. Setelah commit, sistem menampilkan layar konfirmasi pasang. */
    fun install(c: Context, url: String) {
        val b = Net.bytes(url)
        val pi = c.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            s.openWrite("update.apk", 0, b.size.toLong()).use { o -> o.write(b); s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java), flags).intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val n = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (n != null) { n.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(n) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> pushNotif(c, "update", "Pembaruan gagal", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Coba lagi nanti")
        }
    }
}
