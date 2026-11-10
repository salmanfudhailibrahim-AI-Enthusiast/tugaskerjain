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
 * Halaman login akun Google kampus.
 *
 * TANPA Client ID. TANPA SHA-1. TANPA Google Cloud Console.
 * User cukup masuk ke akun Google mereka lewat WebView di dalam aplikasi.
 * Cookie sesi Google Drive otomatis tersimpan untuk mengunduh PDF dosen.
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

        // ========== Bagian Menu (ditampilkan saat belum/setelah login) ==========
        menuBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        menuScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(menuBox)
        }

        // ========== Bagian WebView (login Google) ==========
        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Bar atas WebView
        val webBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(38), dp(12), dp(8))
            setBackgroundColor(0xF00D1321.toInt())
        }
        val btnClose = tv("← Batal", 13f, Pal.text, true).apply {
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { closeWeb() }
        }
        val webTitle = tv("Masuk Akun Google", 13.5f, Pal.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
        }
        val btnDone = tv("✓ Selesai", 13f, Color.WHITE, true).apply {
            background = grad(14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                CookieManager.getInstance().flush()
                GoogleAuth.saveWebSession(this@GoogleActivity)
                Toast.makeText(this@GoogleActivity, "✅ Sesi Google disimpan", Toast.LENGTH_SHORT).show()
                closeWeb()
            }
        }
        webBar.addView(btnClose, LinearLayout.LayoutParams(-2, -2))
        webBar.addView(webTitle, LinearLayout.LayoutParams(0, -2, 1f))
        webBar.addView(btnDone, LinearLayout.LayoutParams(-2, -2))

        // Progress bar
        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val pg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.cyan, Pal.violet))
            progressDrawable = ClipDrawable(pg, Gravity.START, ClipDrawable.HORIZONTAL)
        }

        // WebView
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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
                    checkLoginStatus(url ?: "")
                    // Coba ekstrak email dari halaman
                    v?.evaluateJavascript(
                        "(function(){var e=document.querySelector('[data-email]');return e?e.getAttribute('data-email'):'';})()"
                    ) { result ->
                        val email = result?.trim()?.removeSurrounding("\"") ?: ""
                        if (email.contains("@") && email.endsWith("@${GoogleAuth.DOMAIN}", true)) {
                            prefs().edit().putString("gEmail", email).apply()
                        }
                    }
                }
            }
        }

        webContainer.addView(webBar)
        webContainer.addView(webProgress, LinearLayout.LayoutParams(-1, dp(3)))
        webContainer.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(menuScroll, LinearLayout.LayoutParams(-1, -1))
        root.addView(webContainer, LinearLayout.LayoutParams(-1, -1))

        setContentView(root)
        drawMenu()
    }

    /** Periksa apakah user sudah berhasil masuk Google berdasarkan URL dan cookies. */
    private fun checkLoginStatus(url: String) {
        if (loginDetected) return
        val isOnDrive = url.contains("drive.google.com") ||
                url.contains("myaccount.google.com") ||
                url.contains("google.com/drive")
        if (!isOnDrive) return

        CookieManager.getInstance().flush()
        val ck = CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
        val loggedIn = ck.contains("SID=") || ck.contains("SAPISID=") || ck.contains("__Secure-1PSID")
        if (loggedIn) {
            loginDetected = true
            GoogleAuth.saveWebSession(this)
            ui.post {
                Toast.makeText(this, "✅ Berhasil masuk akun Google!", Toast.LENGTH_LONG).show()
                closeWeb()
            }
        }
    }

    private fun openWeb() {
        isWebMode = true
        loginDetected = false
        menuScroll.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        // Arahkan ke login Google → redirect ke Drive setelah berhasil
        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=wise&passive=1209600&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive&hl=id")
    }

    private fun closeWeb() {
        isWebMode = false
        webContainer.visibility = View.GONE
        menuScroll.visibility = View.VISIBLE
        webView.loadUrl("about:blank")
        drawMenu()
    }

    /** Gambar tampilan menu utama (sebelum & sesudah login). */
    private fun drawMenu() {
        menuBox.removeAllViews()

        // Header dengan tombol kembali
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
            addView(tv("GOOGLE DRIVE", 11f, Pal.cyan, true).apply { letterSpacing = 0.2f })
            addView(tv("Hubungkan akun Google", 22f, Pal.text, true))
        }
        header.addView(btnBack, LinearLayout.LayoutParams(-2, -2))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        menuBox.addView(header)

        menuBox.addView(tv(
            "Supaya aplikasi bisa membaca PDF materi yang ditautkan dosen ke Google Drive (tombol \"Lihat\" di ELITA). Aplikasi hanya meminta izin baca, tidak bisa mengubah atau menghapus file.",
            13f, Pal.mute
        ).apply { setPadding(0, dp(4), 0, dp(16)) })

        if (GoogleAuth.signedIn(this)) {
            // ---- Sudah login ----
            val em = GoogleAuth.email(this).ifBlank { "Akun Google kampus" }
            menuBox.addView(card().apply {
                background = shape(tint(Pal.green, 30), 22, tint(Pal.green, 100))
                addView(chip("✅ TERHUBUNG", Pal.green))
                addView(tv(em, 18f, Pal.text, true).apply { setPadding(0, dp(10), 0, dp(4)) })
                addView(tv("File materi PDF dosen di Google Drive bisa langsung dibuka di aplikasi. Ketuk materi ☁ di tab Kuliah.", 13f, Pal.mute).apply {
                    setPadding(0, dp(4), 0, dp(12))
                })
                addView(row(
                    ghost("📂 Buka Google Drive") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com")).setPackage("com.google.android.apps.docs")) }
                        catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com"))) }
                    },
                    ghost("Keluar", Pal.red) {
                        GoogleAuth.signOut(this@GoogleActivity)
                        drawMenu()
                    }
                ))
            })
        } else {
            // ---- Belum login ----
            menuBox.addView(card().apply {
                addView(tv("Belum terhubung", 16f, Pal.text, true).apply { setPadding(0, 0, 0, dp(10)) })

                val btnLogin = TextView(this@GoogleActivity).apply {
                    text = "🔑  Masuk dengan Google"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = grad(20)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    setOnClickListener { openWeb() }
                }
                addView(btnLogin, LinearLayout.LayoutParams(-1, -2))

                addView(tv(
                    "Halaman login resmi Google dibuka di dalam aplikasi. Kata sandi TIDAK disimpan di aplikasi ini.",
                    11.5f, Pal.mute
                ).apply {
                    setPadding(0, dp(12), 0, 0)
                    gravity = Gravity.CENTER
                })
            })
        }

        // Alternatif tanpa login
        menuBox.addView(card().apply {
            addView(label("Tanpa login (tetap bisa)"))
            addView(tv(
                "1. Di aplikasi Google Drive HP, buka file materi.\n" +
                "2. Ketuk ⋮ → Kirim salinan → Pilih \"Tugas Kampus\".\n" +
                "3. PDF langsung terbuka di aplikasi dan bisa diringkas AI.",
                13f, Pal.text
            ).apply { setLineSpacing(dp(3).toFloat(), 1.1f) })
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isWebMode) {
            if (webView.canGoBack()) webView.goBack() else closeWeb()
        } else {
            super.onBackPressed()
        }
    }
}
