package id.pemantau.tugas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Login lewat SSO kampus (ELITA UNTIDAR).
 * Mendeteksi penyelesaian login SSO secara otomatis dan mengambil token Moodle mobile web service.
 */
class LoginActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var statusTv: TextView
    private var tokenSaved = false
    private var checkingToken = false
    private var redirectedToElita = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val base = prefs().str("baseUrl", "https://elita.untidar.ac.id").trimEnd('/')
        val start = prefs().str("ssoUrl", "https://sso.untidar.ac.id/Home/Login")
        val baseHost = try { Uri.parse(base).host ?: "elita.untidar.ac.id" } catch (e: Exception) { "elita.untidar.ac.id" }

        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = Net.UA
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        // Jembatan JavaScript untuk menerima token dari injeksi DOM
        class Bridge {
            @JavascriptInterface
            fun onTokenFound(token: String) {
                ui.post { handleCandidateToken(token) }
            }

            @JavascriptInterface
            fun onSchemeUrl(url: String) {
                ui.post { parseTokenFromScheme(url) }
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (parseTokenFromScheme(u)) return true
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return false
                if (parseTokenFromScheme(u)) return true
                return false
            }

            override fun onPageFinished(v: WebView?, u: String?) {
                val url = u ?: return
                cm.flush()

                // Jika user selesai login di portal SSO (bukan lagi di halaman form /Home/Login):
                val curHost = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
                if (curHost.contains("sso.untidar.ac.id") && !url.contains("Home/Login", ignoreCase = true) && !redirectedToElita) {
                    redirectedToElita = true
                    statusTv.text = "✅ Login SSO terdeteksi! Membuka ELITA..."
                    statusTv.setTextColor(Pal.cyan)
                    web.loadUrl(base)
                    return
                }

                // Jika sudah berada di halaman ELITA:
                if (curHost == baseHost || url.contains(baseHost)) {
                    val isLoginPage = url.contains("/login/", ignoreCase = true)
                    if (!isLoginPage) {
                        statusTv.text = "✅ Berhasil masuk ELITA! Mengambil token Moodle otomatis..."
                        statusTv.setTextColor(Pal.cyan)
                        triggerAutoFetchToken(web, base)
                    }
                }
            }
        }

        statusTv = tv("Silakan masuk menggunakan akun SSO Untidar Anda...", 11.5f, Pal.mute)

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(10))
            addView(LinearLayout(this@LoginActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("🔐 Login Kampus", 17f, Pal.text, true))
                addView(statusTv)
            }, LinearLayout.LayoutParams(0, -2, 1f))

            addView(gpill("✓ Selesai") {
                cm.flush()
                if (!tokenSaved) {
                    triggerAutoFetchToken(web, base)
                }
                Toast.makeText(this@LoginActivity, "Sesi login kampus disimpan.", Toast.LENGTH_SHORT).show()
                finish()
            }.apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }, LinearLayout.LayoutParams(-2, -2))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = aurora()
            addView(bar)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        handleIntent(intent)
        if (start.isNotEmpty()) web.loadUrl(start)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val dataUri = intent?.data?.toString() ?: return
        parseTokenFromScheme(dataUri)
    }

    private fun parseTokenFromScheme(url: String): Boolean {
        if (!url.startsWith("tugaskerjain://") && !url.startsWith("moodlemobile://")) return false
        val raw = url.substringAfter("token=")
        if (raw.isBlank() || raw == url) return false

        // Pola 1: Base64 dari md5(siteurl+passport):::token:::privatetoken
        try {
            val dec = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            val parts = dec.split(":::")
            if (parts.size >= 2 && Regex("^[a-f0-9]{32}$", RegexOption.IGNORE_CASE).matches(parts[1])) {
                handleCandidateToken(parts[1])
                return true
            }
        } catch (e: Exception) {}

        // Pola 2: token langsung 32 karakter hex
        val m = Regex("\\b[a-f0-9]{32}\\b", RegexOption.IGNORE_CASE).find(raw)
        if (m != null) {
            handleCandidateToken(m.value)
            return true
        }
        return false
    }

    private fun handleCandidateToken(candidate: String) {
        val clean = candidate.trim()
        if (!Regex("^[a-f0-9]{32}$", RegexOption.IGNORE_CASE).matches(clean)) return
        if (tokenSaved) return
        tokenSaved = true

        prefs().edit().putString("token", clean).apply()
        CookieManager.getInstance().flush()

        statusTv.text = "✅ Berhasil! Token Moodle otomatis tersimpan."
        statusTv.setTextColor(Pal.green)
        Toast.makeText(this, "✅ Berhasil login & token Moodle otomatis tersimpan!", Toast.LENGTH_LONG).show()

        ui.postDelayed({
            if (!isFinishing) finish()
        }, 900)
    }

    private fun triggerAutoFetchToken(web: WebView, base: String) {
        if (tokenSaved || checkingToken) return
        checkingToken = true

        // 1. Eksekusi JavaScript di WebView untuk membaca halaman managetoken.php dan launch.php
        val js = """
            (function() {
                try {
                    fetch('/user/managetoken.php', {credentials: 'include'})
                        .then(function(r) { return r.text(); })
                        .then(function(html) {
                            var m = html.match(/\\b[a-f0-9]{32}\\b/i);
                            if (m && window.AndroidBridge) {
                                window.AndroidBridge.onTokenFound(m[0]);
                            }
                        }).catch(function(e){});

                    fetch('/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=tk_' + Date.now() + '&urlscheme=tugaskerjain', {credentials: 'include'})
                        .then(function(r) { return r.text(); })
                        .then(function(html) {
                            var m = html.match(/tugaskerjain:\/\/token=([a-zA-Z0-9+=/]+)/i);
                            if (m && window.AndroidBridge) {
                                window.AndroidBridge.onSchemeUrl(m[0]);
                            }
                        }).catch(function(e){});
                } catch(e) {}
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)

        // 2. Background thread sebagai backup ganda (via Net helper)
        thread {
            try {
                val h = mapOf("Cookie" to Net.cookie(base))
                // Cek managetoken.php
                try {
                    val html = Net.req("$base/user/managetoken.php", headers = h)
                    val m = Regex("\\b[a-f0-9]{32}\\b", RegexOption.IGNORE_CASE).find(html)
                    if (m != null) {
                        ui.post { handleCandidateToken(m.value) }
                        return@thread
                    }
                } catch (e: Exception) {}

                // Cek launch.php
                try {
                    val launchUrl = "$base/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=tk_${System.currentTimeMillis()}&urlscheme=tugaskerjain"
                    val loc = Net.location(launchUrl, h)
                    if (loc != null) {
                        ui.post { parseTokenFromScheme(loc) }
                    }
                } catch (e: Exception) {}
            } finally {
                ui.postDelayed({ checkingToken = false }, 3000)
            }
        }
    }
}
