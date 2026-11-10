package id.pemantau.tugas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import kotlin.concurrent.thread

/**
 * Halaman masuk akun Google Kampus:
 * Memungkinkan mahasiswa masuk langsung lewat akun Google Mail/Drive kampus
 * tanpa repot memasukkan Client ID atau SHA-1.
 */
class GoogleActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var box: LinearLayout
    private lateinit var webContainer: LinearLayout
    private lateinit var webView: WebView
    private lateinit var webProgress: ProgressBar
    private var msg = ""
    private var isWebLoginOpen = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = aurora()
        }

        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }

        setupWebLoginContainer()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(box)
        }

        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        draw()
        handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    private fun handle(i: Intent?) {
        val it = i ?: return
        val d = it.data ?: return
        if (d.scheme != packageName) return
        setIntent(Intent())
        msg = "Menyelesaikan proses masuk..."
        draw()
        thread {
            var err: String? = null
            try {
                GoogleAuth.finish(this, d)
            } catch (e: Exception) {
                err = e.message ?: "gagal"
            }
            ui.post {
                msg = if (err == null) "✅ Berhasil masuk akun Google Kampus!" else "Gagal: $err"
                draw()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebLoginContainer() {
        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(0xF010172A.toInt(), 0)
            visibility = View.GONE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(10))
            background = shape(0xFF0F172A.toInt(), 0)
        }

        val btnClose = TextView(this).apply {
            text = "✕ Tutup"
            textSize = 13f
            setTextColor(Pal.mute)
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { closeWebLogin() }
        }

        val title = tv("Login Google Kampus", 14f, Pal.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }

        topBar.addView(btnClose, LinearLayout.LayoutParams(-2, -2))
        topBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val pg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.cyan, Pal.violet))
            progressDrawable = ClipDrawable(pg, Gravity.START, ClipDrawable.HORIZONTAL)
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView?, newProgress: Int) {
                    webProgress.progress = newProgress
                    webProgress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    val u = url ?: return
                    // Jika berhasil dialihkan ke Google Drive atau MyAccount, sesi login telah aktif!
                    if (u.contains("drive.google.com") || u.contains("myaccount.google.com") || u.contains("google.com/drive")) {
                        CookieManager.getInstance().flush()
                        val cookies = CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
                        if (cookies.contains("SID=") || cookies.contains("OSID=") || cookies.contains("SSID=")) {
                            GoogleAuth.saveWebSession(this@GoogleActivity)
                            Toast.makeText(this@GoogleActivity, "✅ Berhasil masuk akun Google!", Toast.LENGTH_SHORT).show()
                            closeWebLogin()
                            draw()
                        }
                    }
                }
            }
        }

        webContainer.addView(topBar)
        webContainer.addView(webProgress)
        webContainer.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(webContainer, LinearLayout.LayoutParams(-1, -1))
    }

    private fun openWebLogin() {
        isWebLoginOpen = true
        webContainer.visibility = View.VISIBLE
        // Buka halaman login Google dengan tujuan redirect ke Google Drive
        val loginUrl = "https://accounts.google.com/ServiceLogin?service=wise&passive=1209600&continue=https%3A%2F%2Fdrive.google.com%2Fdrive%2Fmy-drive"
        webView.loadUrl(loginUrl)
    }

    private fun closeWebLogin() {
        isWebLoginOpen = false
        webContainer.visibility = View.GONE
        webView.loadUrl("about:blank")
        draw()
    }

    private fun draw() {
        box.removeAllViews()

        // Header
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val btnBack = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Pal.text)
            gravity = Gravity.CENTER
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }

        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(tv("AKUN KAMPUS", 11f, Pal.cyan, true).apply { letterSpacing = 0.2f })
            addView(tv("Google Mail & Drive", 24f, Pal.text, true))
        }

        topRow.addView(btnBack, LinearLayout.LayoutParams(-2, -2))
        topRow.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(topRow)

        box.addView(
            tv(
                "Materi kuliah dan tugas dosen yang tersimpan di Google Drive hanya dapat diakses menggunakan akun kampus (@${GoogleAuth.DOMAIN}). Cukup masuk sekali agar dapat membaca PDF secara langsung di aplikasi.",
                13.5f,
                Pal.mute
            ).apply { setPadding(0, 0, 0, dp(16)) }
        )

        // Status Card
        if (GoogleAuth.signedIn(this)) {
            val userEmail = GoogleAuth.email(this).ifBlank { "Akun Google Kampus" }
            box.addView(card().apply {
                background = shape(tint(Pal.green, 32), 22, tint(Pal.green, 110))
                addView(chip("TERHUBUNG", Pal.green))
                addView(tv("✅ Akun Google Aktif", 18f, Pal.text, true).apply { setPadding(0, dp(10), 0, dp(4)) })
                addView(tv(userEmail, 14f, Pal.cyan).apply { setTextIsSelectable(true) })
                addView(tv("Aplikasi dapat membuka file materi dan PDF dosen di Google Drive secara otomatis.", 12.5f, Pal.mute).apply { setPadding(0, dp(6), 0, dp(12)) })

                val btnDrive = ghost("📂 Buka Google Drive") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com")).setPackage("com.google.android.apps.docs"))
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com")))
                    }
                }
                val btnLogout = ghost("Keluar (Sign Out)", Pal.red) {
                    GoogleAuth.signOut(this@GoogleActivity)
                    msg = "Berhasil keluar dari akun Google."
                    draw()
                }
                addView(row(btnDrive, btnLogout))
            })
        } else {
            box.addView(card().apply {
                addView(label("Masuk dengan Akun Kampus"))
                addView(tv("Gunakan akun email kampusmu (@${GoogleAuth.DOMAIN}) agar otomatis dapat membaca PDF dosen di ELITA.", 13f, Pal.text).apply {
                    setPadding(0, 0, 0, dp(14))
                })

                val btnLogin = TextView(this@GoogleActivity).apply {
                    text = "🚀  Masuk dengan Akun Google"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = grad(18)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    setOnClickListener { openWebLogin() }
                }
                addView(btnLogin, LinearLayout.LayoutParams(-1, -2))

                addView(
                    tv("Login resmi Google dibuka di dalam aplikasi secara aman. Sandi akunmu tidak tersimpan di aplikasi ini.", 11.5f, Pal.mute).apply {
                        setPadding(0, dp(10), 0, 0)
                        gravity = Gravity.CENTER
                    }
                )

                if (msg.isNotEmpty()) {
                    addView(
                        tv(msg, 13f, if (msg.startsWith("Gagal")) Pal.red else Pal.amber).apply {
                            setPadding(0, dp(12), 0, 0)
                            gravity = Gravity.CENTER
                        }
                    )
                }
            })
        }

        // Pilihan Alternatif / Panduan Singkat
        box.addView(card().apply {
            addView(label("Pilihan Tanpa Login di Aplikasi"))
            addView(
                tv(
                    "Kamu juga bisa tetap membaca materi tanpa login di aplikasi dengan cara:\n\n" +
                    "1. Buka materi di aplikasi Google Drive HP milikmu.\n" +
                    "2. Ketuk titik tiga (⋮) pada file PDF → Kirim Salinan → Pilih Tugas Kampus.\n" +
                    "3. Atau saat membuka materi di aplikasi, pilih \"Pilih file dari Drive HP\".",
                    12.5f,
                    Pal.text
                ).apply { setLineSpacing(dp(2).toFloat(), 1.15f) }
            )
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isWebLoginOpen) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                closeWebLogin()
            }
        } else {
            super.onBackPressed()
        }
    }
}
