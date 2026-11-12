package id.pemantau.tugas

import android.content.Context
import android.webkit.CookieManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Login Google lewat browser (OAuth + PKCE, tanpa library tambahan). Hanya meminta izin baca Drive. */
object GoogleAuth {
    private const val AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN = "https://oauth2.googleapis.com/token"
    private val FORM = mapOf("Content-Type" to "application/x-www-form-urlencoded")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun rnd(n: Int): String { val b = ByteArray(n); SecureRandom().nextBytes(b); return b64(b) }

    fun clientId(c: Context): String = c.prefs().str("gClient").trim()
    fun redirect(c: Context): String = c.packageName + ":/oauth2redirect"
    fun signedIn(c: Context): Boolean = c.prefs().str("gRefresh").isNotEmpty()
    fun email(c: Context): String = c.prefs().str("gEmail")

    /** Alamat halaman login Google yang dibuka di browser. */
    fun authUrl(c: Context): String {
        val ver = rnd(48); val st = rnd(16)
        c.prefs().edit().putString("gVerifier", ver).putString("gState", st).apply()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(ver.toByteArray(Charsets.US_ASCII)))
        return Uri.parse(AUTH).buildUpon()
            .appendQueryParameter("client_id", clientId(c))
            .appendQueryParameter("redirect_uri", redirect(c))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "https://www.googleapis.com/auth/drive.readonly openid email")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", st)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build().toString()
    }

    /** Dipanggil saat browser mengembalikan ?code=... ke aplikasi. Blocking: jalankan di thread. */
    fun finish(c: Context, data: Uri) {
        val p = c.prefs()
        val err = data.getQueryParameter("error")
        if (err != null) throw Exception(if (err == "access_denied") "izin ditolak" else err)
        val code = data.getQueryParameter("code") ?: throw Exception("kode login tidak ada")
        if (p.str("gVerifier").isEmpty() || data.getQueryParameter("state") != p.str("gState")) throw Exception("sesi login tidak cocok, coba lagi")
        val body = "client_id=${enc(clientId(c))}&code=${enc(code)}&code_verifier=${enc(p.str("gVerifier"))}&grant_type=authorization_code&redirect_uri=${enc(redirect(c))}"
        save(c, JSONObject(Net.req(TOKEN, "POST", body, FORM)))
        p.edit().remove("gVerifier").remove("gState").apply()
    }

    private fun save(c: Context, r: JSONObject) {
        val e = c.prefs().edit().putString("gAccess", r.getString("access_token"))
            .putLong("gExp", System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000)
        if (r.has("refresh_token")) e.putString("gRefresh", r.getString("refresh_token"))
        val idt = r.optString("id_token")
        if (idt.contains(".")) {
            try { e.putString("gEmail", JSONObject(String(Base64.decode(idt.split(".")[1], Base64.URL_SAFE), Charsets.UTF_8)).optString("email")) } catch (x: Exception) { }
        }
        e.apply()
    }

    /** Token akses yang masih berlaku (diperbarui otomatis). Web session pakai cookie. */
    fun token(c: Context): String {
        val p = c.prefs()
        if (p.str("gRefresh").isEmpty()) throw Exception("belum masuk Google. Buka Pengaturan lalu pilih Hubungkan Google Drive")
        // Mode web-session: tidak ada OAuth token, pakai cookie browser
        if (p.str("gRefresh") == "web-session") return "web-session"
        if (p.str("gAccess").isNotEmpty() && System.currentTimeMillis() < p.getLong("gExp", 0) - 60000) return p.str("gAccess")
        val cid = clientId(c)
        if (cid.isBlank()) throw Exception("Client ID belum diisi dan sesi web sudah kedaluwarsa. Login ulang lewat ikon 🔑")
        val r = JSONObject(Net.req(TOKEN, "POST", "client_id=${enc(cid)}&refresh_token=${enc(p.str("gRefresh"))}&grant_type=refresh_token", FORM))
        save(c, r)
        return r.getString("access_token")
    }

    /** Simpan sesi login Google dari WebView (cookie-based, tanpa OAuth/Client ID). */
    fun saveWebSession(c: Context) {
        val cm = CookieManager.getInstance()
        val ckAcc = cm.getCookie("https://accounts.google.com") ?: ""
        val ckDrive = cm.getCookie("https://drive.google.com") ?: ""
        val allCookies = "$ckAcc; $ckDrive"

        val hasSID = allCookies.contains("SID=") || allCookies.contains("SAPISID=") ||
                allCookies.contains("__Secure-1PSID=") || allCookies.contains("__Secure-3PSID=")
        if (hasSID) {
            c.prefs().edit()
                .putString("gRefresh", "web-session")
                .putString("gAccess", "web-session")
                .putLong("gExp", System.currentTimeMillis() + 365L * 86400000L)
                .apply()
        }
    }

    fun signOut(c: Context) {
        c.prefs().edit().remove("gRefresh").remove("gAccess").remove("gExp").remove("gEmail").apply()
    }

    /** Sidik jari SHA-1 tanda tangan aplikasi; dibutuhkan saat membuat OAuth Client ID Android. */
    @Suppress("DEPRECATION")
    fun sha1(c: Context): String {
        val pm = c.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0]
        else pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures!![0]
        return MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
    }
}

/** Membaca file dan folder Google Drive. Tanpa login hanya file publik (bagikan ke siapa saja yang punya tautan). */
object Drive {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun isDrive(u: String) = Regex("^https?://(drive|docs)\\.google\\.com/").containsMatchIn(u)
    fun fileId(u: String): String? = Regex("/(?:file|document|presentation|spreadsheets)/d/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
        ?: Regex("[?&]id=([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
    fun folderId(u: String): String? = Regex("/folders/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)

    private fun publicDownload(id: String): ByteArray {
        val b = Net.bytes("https://drive.google.com/uc?export=download&id=$id")
        if (b.size < 5 || String(b, 0, 5, Charsets.ISO_8859_1) != "%PDF-") throw Exception("file Drive ini tidak publik atau bukan PDF. Hubungkan akun Google di Pengaturan untuk membacanya")
        return b
    }

    fun download(c: Context, u: String): ByteArray {
        val id = fileId(u) ?: throw Exception(if (folderId(u) != null) "ini folder Drive: hubungkan akun Google lalu muat ulang daftar materi" else "tautan Drive tidak dikenali")
        if (!GoogleAuth.signedIn(c)) return publicDownload(id)
        val tk = GoogleAuth.token(c)
        if (tk == "web-session") {
            // Unduh dengan cookie dari WebView
            val cm = CookieManager.getInstance()
            val ck = cm.getCookie("https://drive.google.com") ?: cm.getCookie("https://accounts.google.com") ?: ""
            val h = if (ck.isNotEmpty()) mapOf("Cookie" to ck) else emptyMap()
            val url = "https://drive.google.com/uc?export=download&id=$id"
            try { return Net.bytes(url, h) } catch (e: Exception) { return publicDownload(id) }
        }
        val h = mapOf("Authorization" to "Bearer $tk")
        try {
            val mt = JSONObject(Net.req("https://www.googleapis.com/drive/v3/files/$id?fields=name,mimeType&supportsAllDrives=true", headers = h)).optString("mimeType")
            val gdoc = mt.startsWith("application/vnd.google-apps")
            if (!gdoc && mt != "application/pdf") throw Exception("berkas Drive ini bukan PDF ($mt)")
            val url = if (gdoc) "https://www.googleapis.com/drive/v3/files/$id/export?mimeType=application/pdf" else "https://www.googleapis.com/drive/v3/files/$id?alt=media&supportsAllDrives=true"
            return Net.bytes(url, h)
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (m.startsWith("HTTP 401") || m.startsWith("HTTP 403") || m.startsWith("HTTP 404"))
                throw Exception("Drive menolak akses ($m). Pastikan file dibagikan ke akun Google yang kamu pakai masuk")
            throw e
        }
    }

    /** PDF, Slides, dan Docs di dalam satu folder Drive (butuh login Google). */
    fun folderFiles(c: Context, folderId: String): List<Att> {
        val tk = GoogleAuth.token(c)
        val h = if (tk == "web-session") {
            val cm = CookieManager.getInstance()
            val ck = cm.getCookie("https://www.googleapis.com") ?: cm.getCookie("https://accounts.google.com") ?: ""
            mapOf("Cookie" to ck)
        } else mapOf("Authorization" to "Bearer $tk")
        val q = "'$folderId' in parents and trashed=false and (mimeType='application/pdf' or mimeType='application/vnd.google-apps.presentation' or mimeType='application/vnd.google-apps.document')"
        val r = JSONObject(Net.req("https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=${enc("files(id,name,mimeType)")}&pageSize=100&supportsAllDrives=true&includeItemsFromAllDrives=true", headers = h))
        val a = r.optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { val f = a.getJSONObject(it); Att(f.optString("name"), "https://drive.google.com/file/d/${f.getString("id")}/view") }
    }
}

/** Satu pintu untuk membaca isi lampiran: file HP, berkas Moodle, tautan URL Moodle, atau Google Drive. */
object Docs {
    private fun cookie(c: Context) = mapOf("Cookie" to Net.cookie(c.prefs().str("baseUrl")))

    /** Tautan "mod/url" dan "mod/resource" Moodle diubah menjadi alamat tujuan sebenarnya (mis. pluginfile atau Google Drive). */
    fun resolve(c: Context, u0: String): String {
        val base = c.prefs().str("baseUrl").trimEnd('/')
        val ck = cookie(c)
        var u = u0

        if (u.contains("/mod/url/view.php")) {
            val withRedirect = if (u.contains("redirect=")) u else u + (if (u.contains("?")) "&" else "?") + "redirect=1"
            val loc = Net.location(withRedirect, ck)
            if (loc != null) return loc
            val html = try { Net.req(u, headers = ck) } catch (_: Exception) { "" }
            return Regex("<a[^>]+href=\"(https?://[^\"]+)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.firstOrNull { !it.startsWith(base) } ?: u
        }

        if (u.contains("/mod/resource/view.php")) {
            val loc = Net.location(u, ck)
            if (loc != null && loc.contains("pluginfile.php")) return loc
            var html = try { Net.req(u, headers = ck) } catch (_: Exception) { "" }
            if (html.isBlank() || html.contains("Just a moment") || html.contains("cf-mitigated")) {
                html = WebEngine.getHtml(c, u)
            }
            val match = Regex("""(?:src|data|href)=["']([^"']*pluginfile\.php[^"']*)["']""").find(html)
            if (match != null) {
                var found = match.groupValues[1].replace("&amp;", "&")
                if (found.startsWith("/")) found = base + found
                return found
            }
        }
        return u
    }

    fun bytes(c: Context, a: Att): ByteArray {
        if (a.uri != null) return c.contentResolver.openInputStream(a.uri)?.use { it.readBytes() } ?: ByteArray(0)
        val u = resolve(c, a.url!!)
        if (Drive.isDrive(u)) return Drive.download(c, u)
        val base = c.prefs().str("baseUrl").trimEnd('/')
        val ck = if (base.isNotEmpty() && u.startsWith(base)) cookie(c) else emptyMap()

        // 1. Coba download langsung via Net.bytes
        val res = try { Net.bytes(u, ck) } catch (e: Exception) { ByteArray(0) }
        val isPdf = res.size >= 5 && String(res, 0, 5, Charsets.ISO_8859_1) == "%PDF-"
        if (isPdf) return res

        // 2. Jika bukan PDF valid dan URL berada di Moodle kampus, gunakan WebEngine dengan sesi WebView browser
        if (base.isNotEmpty() && (u.startsWith(base) || a.url!!.startsWith(base))) {
            val target = if (u.contains("pluginfile.php")) u else a.url!!
            val webBytes = WebEngine.getBytes(c, target)
            if (webBytes != null && webBytes.size >= 5 && String(webBytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-") {
                return webBytes
            }
        }
        return res
    }
}
