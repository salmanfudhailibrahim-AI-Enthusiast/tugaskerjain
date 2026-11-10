package id.pemantau.tugas

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Autentikasi Google untuk mahasiswa:
 * Mendukung login langsung (sesi web & cookie Google Drive) tanpa perlu repot mengisi Client ID / SHA-1,
 * serta mendukung OAuth + PKCE otomatis jika tersedia.
 */
object GoogleAuth {
    private const val AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN = "https://oauth2.googleapis.com/token"
    private val FORM = mapOf("Content-Type" to "application/x-www-form-urlencoded")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun rnd(n: Int): String { val b = ByteArray(n); SecureRandom().nextBytes(b); return b64(b) }

    /** Domain resmi akun Google kampus. */
    const val DOMAIN = "students.untidar.ac.id"

    /** Client ID bawaan / otomatis (fallback) agar pengguna tidak perlu repot mengisi manual. */
    const val DEFAULT_CLIENT_ID = ""

    fun clientId(c: Context): String = c.prefs().str("gClient").trim().ifEmpty { DEFAULT_CLIENT_ID }
    fun redirect(c: Context): String = c.packageName + ":/oauth2redirect"

    /** Cek apakah sudah terhubung ke Google (baik lewat sesi web WebView maupun OAuth). */
    fun signedIn(c: Context): Boolean {
        val p = c.prefs()
        if (p.str("gRefresh").isNotEmpty()) return true
        if (p.getBoolean("gWebSignedIn", false)) return true
        val ck = CookieManager.getInstance().getCookie("https://drive.google.com") ?: p.str("gCookies")
        return ck.contains("SID=") || ck.contains("OSID=")
    }

    fun email(c: Context): String {
        val em = c.prefs().str("gEmail")
        return if (em.isNotBlank()) em else if (signedIn(c)) "Akun Google Kampus" else ""
    }

    /** Simpan sesi login web (cookies) dari WebView. */
    fun saveWebSession(c: Context, email: String = "") {
        val ck = CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
        val p = c.prefs().edit().putBoolean("gWebSignedIn", true).putString("gCookies", ck)
        if (email.isNotBlank()) p.putString("gEmail", email)
        p.apply()
    }

    /** Alamat halaman login Google OAuth untuk browser luar (jika Client ID tersedia). */
    fun authUrl(c: Context, hint: String = ""): String {
        val cid = clientId(c)
        val ver = rnd(48); val st = rnd(16)
        c.prefs().edit().putString("gVerifier", ver).putString("gState", st).apply()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(ver.toByteArray(Charsets.US_ASCII)))
        return Uri.parse(AUTH).buildUpon()
            .appendQueryParameter("client_id", cid)
            .appendQueryParameter("redirect_uri", redirect(c))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "https://www.googleapis.com/auth/drive.readonly openid email")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", st)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("hd", DOMAIN)
            .apply { if (hint.isNotBlank()) appendQueryParameter("login_hint", hint) }
            .build().toString()
    }

    /** Dipanggil saat browser mengembalikan redirect OAuth. */
    fun finish(c: Context, data: Uri) {
        val p = c.prefs()
        val err = data.getQueryParameter("error")
        if (err != null) throw Exception(if (err == "access_denied") "izin ditolak" else err)
        val code = data.getQueryParameter("code") ?: throw Exception("kode login tidak ada")
        if (p.str("gVerifier").isEmpty() || data.getQueryParameter("state") != p.str("gState")) throw Exception("sesi login tidak cocok, silakan coba lagi")
        val body = "client_id=${enc(clientId(c))}&code=${enc(code)}&code_verifier=${enc(p.str("gVerifier"))}&grant_type=authorization_code&redirect_uri=${enc(redirect(c))}"
        save(c, JSONObject(Net.req(TOKEN, "POST", body, FORM)))
        p.edit().remove("gVerifier").remove("gState").apply()
        val em = email(c)
        if (em.isNotBlank() && !em.endsWith("@$DOMAIN", ignoreCase = true)) {
            signOut(c)
            throw Exception("akun ${em.ifBlank { "tadi" }} bukan @$DOMAIN. Masuk dengan akun kampusmu")
        }
    }

    private fun save(c: Context, r: JSONObject) {
        val e = c.prefs().edit().putString("gAccess", r.getString("access_token"))
            .putLong("gExp", System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000)
        if (r.has("refresh_token")) e.putString("gRefresh", r.getString("refresh_token"))
        val idt = r.optString("id_token")
        if (idt.contains(".")) {
            try {
                e.putString("gEmail", JSONObject(String(Base64.decode(idt.split(".")[1], Base64.URL_SAFE), Charsets.UTF_8)).optString("email"))
            } catch (x: Exception) { }
        }
        e.apply()
    }

    /** Token akses OAuth yang masih berlaku. */
    fun token(c: Context): String? {
        val p = c.prefs()
        if (p.str("gRefresh").isEmpty()) return null
        if (p.str("gAccess").isNotEmpty() && System.currentTimeMillis() < p.getLong("gExp", 0) - 60000) return p.str("gAccess")
        return try {
            val r = JSONObject(Net.req(TOKEN, "POST", "client_id=${enc(clientId(c))}&refresh_token=${enc(p.str("gRefresh"))}&grant_type=refresh_token", FORM))
            save(c, r)
            r.getString("access_token")
        } catch (e: Exception) { null }
    }

    fun signOut(c: Context) {
        c.prefs().edit()
            .remove("gRefresh").remove("gAccess").remove("gExp").remove("gEmail")
            .remove("gWebSignedIn").remove("gCookies")
            .apply()
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) { }
    }
}

/**
 * Membaca dan mengunduh berkas Google Drive dosen dari ELITA:
 * Mendukung akun Google yang login langsung lewat aplikasi (cookies sesi)
 * maupun akun OAuth resmi.
 */
object Drive {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun isDrive(u: String) = Regex("^https?://(drive|docs)\\.google\\.com/").containsMatchIn(u)
    fun fileId(u: String): String? = Regex("/(?:file|document|presentation|spreadsheets)/d/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
        ?: Regex("[?&]id=([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
    fun folderId(u: String): String? = Regex("/folders/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)

    private fun publicDownload(id: String): ByteArray {
        val b = Net.bytes("https://drive.google.com/uc?export=download&id=$id")
        if (b.size < 5 || String(b, 0, 5, Charsets.ISO_8859_1) != "%PDF-") {
            throw Exception("berkas Drive ini memerlukan login akun Google kampus")
        }
        return b
    }

    /** Unduh berkas PDF dari Google Drive dengan autentikasi yang ada. */
    fun download(c: Context, u: String): ByteArray {
        val id = fileId(u) ?: throw Exception(if (folderId(u) != null) "ini folder Drive: silakan buka lewat aplikasi Google Drive" else "tautan Drive tidak dikenali")

        // 1. Coba OAuth API jika token tersedia
        val tok = GoogleAuth.token(c)
        if (tok != null) {
            try {
                val key = Regex("resourcekey=([^&#]+)").find(u)?.groupValues?.get(1)
                val h = mapOf("Authorization" to "Bearer $tok") + (if (key != null) mapOf("X-Goog-Drive-Resource-Keys" to "$id/$key") else emptyMap())
                val mt = JSONObject(Net.req("https://www.googleapis.com/drive/v3/files/$id?fields=name,mimeType&supportsAllDrives=true", headers = h)).optString("mimeType")
                val gdoc = mt.startsWith("application/vnd.google-apps")
                if (!gdoc && mt != "application/pdf") throw Exception("berkas Drive ini bukan PDF ($mt)")
                val url = if (gdoc) "https://www.googleapis.com/drive/v3/files/$id/export?mimeType=application/pdf" else "https://www.googleapis.com/drive/v3/files/$id?alt=media&supportsAllDrives=true"
                val b = Net.bytes(url, h)
                if (b.size >= 5 && String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return b
            } catch (e: Exception) {
                // Lanjut ke cara web cookies jika OAuth gagal
            }
        }

        // 2. Coba unduh menggunakan sesi Google (CookieManager / Web Login)
        val cookies = CookieManager.getInstance().getCookie("https://drive.google.com") ?: c.prefs().str("gCookies")
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://drive.google.com/"
        )
        if (cookies.isNotBlank()) {
            headers["Cookie"] = cookies
        }

        val candidateUrls = listOf(
            "https://drive.usercontent.google.com/download?id=$id&export=download&authuser=0",
            "https://drive.google.com/uc?export=download&id=$id&confirm=t",
            "https://docs.google.com/document/d/$id/export?format=pdf",
            "https://docs.google.com/presentation/d/$id/export/pdf"
        )

        for (curUrl in candidateUrls) {
            try {
                val bytes = Net.bytes(curUrl, headers)
                if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-") {
                    return bytes
                }
                // Jika Google memberikan halaman konfirmasi virus scan untuk file besar
                val text = String(bytes.take(2000).toByteArray(), Charsets.UTF_8)
                val confirmToken = Regex("confirm=([0-9A-Za-z_-]+)").find(text)?.groupValues?.get(1)
                if (confirmToken != null) {
                    val confirmUrl = "https://drive.google.com/uc?export=download&id=$id&confirm=$confirmToken"
                    val bConfirm = Net.bytes(confirmUrl, headers)
                    if (bConfirm.size >= 5 && String(bConfirm, 0, 5, Charsets.ISO_8859_1) == "%PDF-") {
                        return bConfirm
                    }
                }
            } catch (e: Exception) { }
        }

        // 3. Fallback ke unduhan publik
        try {
            return publicDownload(id)
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (!GoogleAuth.signedIn(c)) {
                throw Exception("File ini di Google Drive dosen. Masuk akun Google Kampus di ikon 🔑 atas agar dapat membacanya.")
            }
            throw Exception("Google Drive menolak akses ($m). Pastikan file dibagikan ke akun kampus @${GoogleAuth.DOMAIN}")
        }
    }

    /** Membaca daftar berkas di dalam folder Google Drive. */
    fun folderFiles(c: Context, folderId: String): List<Att> {
        val tok = GoogleAuth.token(c) ?: return emptyList()
        val h = mapOf("Authorization" to "Bearer $tok")
        val q = "'$folderId' in parents and trashed=false and (mimeType='application/pdf' or mimeType='application/vnd.google-apps.presentation' or mimeType='application/vnd.google-apps.document')"
        val r = JSONObject(Net.req("https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=${enc("files(id,name,mimeType)")}&pageSize=100&supportsAllDrives=true&includeItemsFromAllDrives=true", headers = h))
        val a = r.optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map {
            val f = a.getJSONObject(it)
            Att(f.optString("name"), "https://drive.google.com/file/d/${f.getString("id")}/view")
        }
    }
}

/** Satu pintu untuk membaca isi lampiran: file HP, berkas Moodle, tautan URL Moodle, atau Google Drive. */
object Docs {
    private fun cookie(c: Context) = mapOf("Cookie" to Net.cookie(c.prefs().str("baseUrl")))

    /** Tautan "mod/url" Moodle diubah menjadi alamat tujuan sebenarnya (mis. Google Drive). */
    fun resolve(c: Context, u0: String): String {
        if (!u0.contains("/mod/url/view.php")) return u0
        val base = c.prefs().str("baseUrl").trimEnd('/')
        val withRedirect = if (u0.contains("redirect=")) u0 else u0 + (if (u0.contains("?")) "&" else "?") + "redirect=1"
        val loc = Net.location(withRedirect, cookie(c))
        if (loc != null) return loc
        val html = Net.req(u0, headers = cookie(c))
        return Regex("<a[^>]+href=\"(https?://[^\"]+)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.firstOrNull { !it.startsWith(base) } ?: u0
    }

    fun bytes(c: Context, a: Att): ByteArray {
        if (a.uri != null) return c.contentResolver.openInputStream(a.uri)?.use { it.readBytes() } ?: ByteArray(0)
        val u = resolve(c, a.url!!)
        if (Drive.isDrive(u)) return Drive.download(c, u)
        val base = c.prefs().str("baseUrl").trimEnd('/')
        return Net.bytes(u, if (base.isNotEmpty() && u.startsWith(base)) cookie(c) else emptyMap())
    }
}
