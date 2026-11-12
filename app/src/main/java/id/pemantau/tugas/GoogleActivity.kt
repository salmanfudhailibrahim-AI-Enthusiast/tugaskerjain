package id.pemantau.tugas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Halaman login akun Google langsung (Tanpa Client ID).
 * Begitu dibuka saat belum login, langsung menampilkan halaman login Google.
 */
class GoogleActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var menuScroll: ScrollView
    private lateinit var menuBox: LinearLayout
    private lateinit var webContainer: LinearLayout
    private lateinit var webView: WebView
    private lateinit var webProgress: ProgressBar
    private var isWebMode = false
    private var loginDetected = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = aurora()
        }

        // ========== Bagian Menu (ditampilkan setelah login) ==========
        menuBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        menuScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(menuBox)
        }

        // ========== Bagian WebView (login Google langsung) ==========
        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val webBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(38), dp(12), dp(8))
            setBackgroundColor(0xF00D1321.toInt())
        }
        val btnClose = tv("← Kembali", 13f, Pal.text, true).apply {
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                if (GoogleAuth.signedIn(this@GoogleActivity)) closeWeb() else finish()
            }
        }
        val webTitle = tv("Login Akun Google", 13.5f, Pal.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        val btnDone = tv("✓ Simpan", 13f, Color.WHITE, true).apply {
            background = grad(14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                CookieManager.getInstance().flush()
                GoogleAuth.saveWebSession(this@GoogleActivity)
                Toast.makeText(this@GoogleActivity, "✅ Sesi Google disimpan", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        webBar.addView(btnClose, LinearLayout.LayoutParams(-2, -2))
        webBar.addView(webTitle, LinearLayout.LayoutParams(0, -2, 1f))
        webBar.addView(btnDone, LinearLayout.LayoutParams(-2, -2))

        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val pg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.burgundyLight, Pal.rose))
            progressDrawable = ClipDrawable(pg, Gravity.START, ClipDrawable.HORIZONTAL)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView?, p: Int) {
                    webProgress.progress = p
                    webProgress.visibility = if (p >= 100) View.GONE else View.VISIBLE
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    checkLoginStatus(url ?: "")
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    v?.evaluateJavascript(
                        "(function(){var e=document.querySelector('[data-email]')||document.querySelector('[data-identifier]');return e?(e.getAttribute('data-email')||e.getAttribute('data-identifier')):'';})()"
                    ) { result ->
                        val email = result?.trim()?.removeSurrounding("\"") ?: ""
                        if (email.contains("@")) {
                            prefs().edit().putString("gEmail", email).apply()
                        }
                    }
                    checkLoginStatus(url ?: "")
                }
            }
        }

        webContainer.addView(webBar)
        webContainer.addView(webProgress, LinearLayout.LayoutParams(-1, dp(3)))
        webContainer.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(menuScroll, LinearLayout.LayoutParams(-1, -1))
        root.addView(webContainer, LinearLayout.LayoutParams(-1, -1))

        setContentView(root)

        if (!GoogleAuth.signedIn(this)) {
            openWeb()
        } else {
            drawMenu()
        }
    }

    private fun checkLoginStatus(url: String) {
        if (loginDetected) return
        val cm = CookieManager.getInstance()
        cm.flush()

        val ckDrive = cm.getCookie("https://drive.google.com") ?: ""
        val ckAcc = cm.getCookie("https://accounts.google.com") ?: ""
        val allCookies = "$ckDrive; $ckAcc"

        val hasAuthCookie = allCookies.contains("SID=") ||
                allCookies.contains("SAPISID=") ||
                allCookies.contains("__Secure-1PSID=") ||
                allCookies.contains("__Secure-3PSID=")

        val finishedLoginFlow = url.contains("drive.google.com") ||
                url.contains("myaccount.google.com") ||
                url.contains("ManageAccount") ||
                url.contains("google.com/drive")

        if (hasAuthCookie && finishedLoginFlow) {
            loginDetected = true
            GoogleAuth.saveWebSession(this)
            ui.post {
                Toast.makeText(this, "✅ Berhasil masuk akun Google!", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun openWeb() {
        isWebMode = true
        loginDetected = false
        menuScroll.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=wise&passive=1209600&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive&hl=id")
    }

    private fun closeWeb() {
        isWebMode = false
        webContainer.visibility = View.GONE
        menuScroll.visibility = View.VISIBLE
        webView.loadUrl("about:blank")
        drawMenu()
    }

    private fun drawMenu() {
        menuBox.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val btnBack = tv("←", 22f, Pal.text).apply {
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(tv("GOOGLE DRIVE", 11f, Pal.rose, true).apply { letterSpacing = 0.2f })
            addView(tv("Akun Google Terhubung", 22f, Pal.text, true))
        }
        header.addView(btnBack, LinearLayout.LayoutParams(-2, -2))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        menuBox.addView(header)

        if (GoogleAuth.signedIn(this)) {
            val em = GoogleAuth.email(this).ifBlank { "Akun Google Aktif" }
            menuBox.addView(card().apply {
                background = shape(tint(Pal.green, 30), 22, tint(Pal.green, 100))
                addView(chip("✅ TERHUBUNG", Pal.green))
                addView(tv(em, 18f, Pal.text, true).apply { setPadding(0, dp(10), 0, dp(4)) })
                addView(tv("Sesi Google Drive aktif. Kamu bisa langsung membuka PDF dosen atau drag & drop PDF ke aplikasi.", 13f, Pal.mute).apply {
                    setPadding(0, dp(4), 0, dp(12))
                })
                addView(row(
                    ghost("🔄 Ganti Akun") { openWeb() },
                    ghost("Keluar", Pal.red) {
                        GoogleAuth.signOut(this@GoogleActivity)
                        drawMenu()
                    }
                ))
            })
        } else {
            menuBox.addView(card().apply {
                addView(tv("Belum terhubung", 16f, Pal.text, true).apply { setPadding(0, 0, 0, dp(10)) })
                addView(gpill("🔑  Login ke Google Sekarang") { openWeb() }.apply {
                    textSize = 15f
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }, LinearLayout.LayoutParams(-1, -2))
            })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isWebMode) {
            if (webView.canGoBack()) webView.goBack()
            else if (GoogleAuth.signedIn(this)) closeWeb()
            else finish()
        } else {
            super.onBackPressed()
        }
    }
}
