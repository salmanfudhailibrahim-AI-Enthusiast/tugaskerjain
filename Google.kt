package id.pemantau.tugas

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Login Google untuk mahasiswa — TANPA Client ID, TANPA SHA-1, TANPA Google Cloud Console.
 * Cukup masuk lewat WebView di dalam aplikasi, cookie otomatis tersimpan.
 */
object GoogleAuth {
    const val DOMAIN = "students.untidar.ac.id"

    /** Cek apakah sudah login Google (cookie tersimpan). */
    fun signedIn(c: Context): Boolean {
        if (c.prefs().getBoolean("gWebSignedIn", false)) return true
        return hasDriveCookies()
    }

    /** Ambil cookie Google Drive dari CookieManager dan SharedPreferences. */
    fun driveCookies(c: Context): String {
        val cm = CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
        val saved = c.prefs().str("gCookies")
        // Gabungkan keduanya, prioritas CookieManager (lebih fresh)
        return if (cm.isNotBlank()) cm else saved
    }

    private fun hasDriveCookies(): Boolean {
        val ck = CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
        return ck.contains("SID=") || ck.contains("SAPISID=") || ck.contains("__Secure-1PSID")
    }

    fun email(c: Context): String {
        val em = c.prefs().str("gEmail")
        return if (em.isNotBlank()) em else if (signedIn(c)) "Akun kampus" else ""
    }

    /** Simpan bahwa user sudah login via WebView. Dipanggil dari GoogleActivity. */
    fun saveWebSession(c: Context, email: String = "") {
        // Simpan semua cookies Google yang relevan
        val domains = listOf(
            "https://drive.google.com",
            "https://docs.google.com",
            "https://accounts.google.com",
            "https://www.google.com"
        )
        val allCookies = StringBuilder()
        val cm = CookieManager.getInstance()
        for (d in domains) {
            val ck = cm.getCookie(d)
            if (!ck.isNullOrBlank()) {
                if (allCookies.isNotEmpty()) allCookies.append("; ")
                allCookies.append(ck)
            }
        }
        cm.flush()

        val ed = c.prefs().edit()
            .putBoolean("gWebSignedIn", true)
            .putString("gCookies", allCookies.toString())
        if (email.isNotBlank()) ed.putString("gEmail", email)
        ed.apply()
    }

    fun signOut(c: Context) {
        c.prefs().edit()
            .remove("gWebSignedIn").remove("gCookies").remove("gEmail")
            .remove("gRefresh").remove("gAccess").remove("gExp")
            .remove("gClient").remove("gVerifier").remove("gState")
            .apply()
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) { }
    }
}

/**
 * Unduh file dari Google Drive menggunakan cookie sesi Google
 * (bukan OAuth API, jadi tidak perlu Client ID).
 */
object Drive {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun isDrive(u: String) = Regex("^https?://(drive|docs)\\.google\\.com/").containsMatchIn(u)

    fun fileId(u: String): String? =
        Regex("/(?:file|document|presentation|spreadsheets)/d/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
            ?: Regex("[?&]id=([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)

    fun folderId(u: String): String? =
        Regex("/folders/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)

    /** Header standar agar Google tidak menolak request (berpura-pura browser biasa). */
    private fun browserHeaders(c: Context): Map<String, String> {
        val cookies = GoogleAuth.driveCookies(c)
        val h = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://drive.google.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        if (cookies.isNotBlank()) h["Cookie"] = cookies
        return h
    }

    /** Unduh PDF dari Google Drive. Otomatis pakai cookie sesi kalau sudah login. */
    fun download(c: Context, u: String): ByteArray {
        val id = fileId(u)
            ?: throw Exception(
                if (folderId(u) != null) "ini folder Drive, bukan file. Buka lewat aplikasi Google Drive"
                else "tautan Drive tidak dikenali"
            )

        val h = browserHeaders(c)
        val hasCookies = h.containsKey("Cookie") && h["Cookie"]!!.isNotBlank()

        val rk = Regex("resourcekey=([^&#]+)").find(u)?.groupValues?.get(1)
        val rkParam = if (rk != null) "&resourcekey=$rk" else ""
        if (rk != null) h["X-Goog-Drive-Resource-Keys"] = "$id/$rk"

        // Daftar URL yang akan dicoba berurutan
        val urls = mutableListOf<String>()

        if (hasCookies) {
            // URL untuk file biasa (PDF langsung)
            urls += "https://drive.usercontent.google.com/download?id=$id&export=download&authuser=0$rkParam"
            // URL cadangan
            urls += "https://drive.google.com/uc?export=download&id=$id&confirm=t$rkParam"
            // URL untuk Google Docs → ekspor PDF
            urls += "https://docs.google.com/document/d/$id/export?format=pdf$rkParam"
            // URL untuk Google Slides → ekspor PDF
            urls += "https://docs.google.com/presentation/d/$id/export/pdf$rkParam"
        }

        // URL publik (tanpa login)
        urls += "https://drive.google.com/uc?export=download&id=$id$rkParam"

        for (url in urls) {
            try {
                val bytes = Net.bytes(url, h)

                // Cek apakah hasilnya memang PDF
                if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-") {
                    return bytes
                }

                // Mungkin ada halaman konfirmasi virus-scan dari Google
                if (bytes.size in 100..50000) {
                    val html = String(bytes, Charsets.UTF_8)
                    val confirmMatch = Regex("confirm=([0-9A-Za-z_-]+)").find(html)
                        ?: Regex("id=\"uc-download-link\"[^>]*href=\"([^\"]+)\"").find(html)
                    if (confirmMatch != null) {
                        val confirmUrl = if (confirmMatch.groupValues[1].startsWith("http")) {
                            confirmMatch.groupValues[1].replace("&amp;", "&")
                        } else {
                            "https://drive.google.com/uc?export=download&id=$id&confirm=${confirmMatch.groupValues[1]}"
                        }
                        val b2 = Net.bytes(confirmUrl, h)
                        if (b2.size >= 5 && String(b2, 0, 5, Charsets.ISO_8859_1) == "%PDF-") {
                            return b2
                        }
                    }
                }
            } catch (_: Exception) { /* coba URL berikutnya */ }
        }

        // Semua gagal
        if (!hasCookies) {
            throw Exception("File ini di Google Drive dosen dan memerlukan login.\nKetuk ikon 🔑 di beranda untuk masuk akun Google kampus.")
        }
        throw Exception("Gagal mengunduh file dari Google Drive.\nPastikan file dibagikan ke akun @${GoogleAuth.DOMAIN} milikmu.")
    }

    /** Daftar file di dalam satu folder Google Drive (butuh cookie sesi). */
    fun folderFiles(c: Context, fid: String): List<Att> {
        // Folder listing butuh API, yang butuh OAuth token.
        // Karena kita pakai cookie-only, kita tidak bisa list folder.
        // Sebagai gantinya, kembalikan empty agar UI menangani fallback.
        return emptyList()
    }
}

/** Satu pintu untuk membaca isi lampiran: file HP, berkas Moodle, tautan URL Moodle, atau Google Drive. */
object Docs {
    private fun cookie(c: Context) = mapOf("Cookie" to Net.cookie(c.prefs().str("baseUrl")))

    /** Tautan "mod/url" atau "mod/resource" Moodle diubah menjadi alamat tujuan sebenarnya (mis. Google Drive). */
    fun resolve(c: Context, u0: String): String {
        if (!u0.contains("/mod/url/view.php") && !u0.contains("/mod/resource/view.php")) return u0
        val base = c.prefs().str("baseUrl").trimEnd('/')
        val withRedirect = if (u0.contains("redirect=")) u0 else u0 + (if (u0.contains("?")) "&" else "?") + "redirect=1"
        val loc = Net.location(withRedirect, cookie(c)) ?: Net.location(u0, cookie(c))
        if (loc != null) return loc
        val html = Net.req(u0, headers = cookie(c))
        return Regex("<a[^>]+href=\"(https?://[^\"]+)\"").findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .firstOrNull { !it.startsWith(base) } ?: u0
    }

    fun bytes(c: Context, a: Att): ByteArray {
        if (a.uri != null) return c.contentResolver.openInputStream(a.uri)?.use { it.readBytes() } ?: ByteArray(0)
        val u = resolve(c, a.url!!)
        if (Drive.isDrive(u)) return Drive.download(c, u)
        val base = c.prefs().str("baseUrl").trimEnd('/')
        return Net.bytes(u, if (base.isNotEmpty() && u.startsWith(base)) cookie(c) else emptyMap())
    }
}
