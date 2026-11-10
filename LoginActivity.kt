package id.pemantau.tugas

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Peramban in-app ELITA & SSO kampus dengan navigasi lengkap:
 * Kembali, Halaman Berikutnya, Muat Ulang, Beranda ELITA, indikator progres, dan Simpan Sesi.
 */
class LoginActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var btnBack: TextView
    private lateinit var btnForward: TextView
    private lateinit var titleTv: TextView
    private lateinit var urlTv: TextView
    private lateinit var progress: ProgressBar
    private var closing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val base = prefs().str("baseUrl", "https://elita.untidar.ac.id")
        val start = prefs().str("ssoUrl", base)

        // Toolbar Navigasi
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(0xF012182D.toInt(), 0)
            setPadding(dp(12), dp(36), dp(12), dp(8))
        }

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun navBtn(icon: String, tooltip: String, onClick: () -> Unit) = TextView(this).apply {
            text = icon
            textSize = 16f
            setTextColor(Pal.text)
            gravity = Gravity.CENTER
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }

        btnBack = navBtn("◀", "Kembali") {
            if (web.canGoBack()) web.goBack()
        }
        btnForward = navBtn("▶", "Halaman Berikutnya") {
            if (web.canGoForward()) web.goForward()
        }
        val btnRefresh = navBtn("↻", "Muat Ulang") {
            web.reload()
        }
        val btnHome = navBtn("🏠", "Beranda ELITA") {
            web.loadUrl(base)
        }
        val btnDone = TextView(this).apply {
            text = "✓ Selesai"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = grad(16)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                closing = true
                CookieManager.getInstance().flush()
                Toast.makeText(this@LoginActivity, "✅ Sesi ELITA disimpan", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        navRow.addView(btnBack, LinearLayout.LayoutParams(-2, -2))
        navRow.addView(btnForward, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        navRow.addView(btnRefresh, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        navRow.addView(btnHome, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            titleTv = tv("ELITA Kampus", 13f, Pal.text, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            urlTv = tv(try { Uri.parse(start).host ?: "elita" } catch (e: Exception) { "elita" }, 10f, Pal.mute).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(titleTv)
            addView(urlTv)
        }
        navRow.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        navRow.addView(btnDone, LinearLayout.LayoutParams(-2, -2))

        // Progress bar horizontal tipis di bawah header
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val pg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.cyan, Pal.violet))
            progressDrawable = ClipDrawable(pg, Gravity.START, ClipDrawable.HORIZONTAL)
            layoutParams = LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(6) }
            visibility = View.VISIBLE
        }

        bar.addView(navRow)
        bar.addView(progress)

        // Inisialisasi WebView
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    if (newProgress >= 100) {
                        progress.visibility = View.GONE
                    } else {
                        progress.visibility = View.VISIBLE
                    }
                    updateNavButtons()
                }

                override fun onReceivedTitle(v: WebView?, t: String?) {
                    t?.let { if (it.isNotBlank()) titleTv.text = it }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                    u?.let { urlTv.text = try { Uri.parse(it).host ?: it } catch (e: Exception) { it } }
                    updateNavButtons()
                }

                override fun onPageFinished(v: WebView?, u: String?) {
                    val url = u ?: return
                    urlTv.text = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
                    updateNavButtons()
                    if (closing && Uri.parse(url).host == Uri.parse(base).host && !url.contains("login")) {
                        CookieManager.getInstance().flush()
                        Toast.makeText(this@LoginActivity, "✅ Berhasil login ke ELITA", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }

            if (start.isNotEmpty()) loadUrl(start)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(Pal.bg0)
            addView(bar)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        updateNavButtons()
    }

    private fun updateNavButtons() {
        val canBack = web.canGoBack()
        val canFwd = web.canGoForward()
        btnBack.alpha = if (canBack) 1.0f else 0.35f
        btnBack.isEnabled = canBack
        btnForward.alpha = if (canFwd) 1.0f else 0.35f
        btnForward.isEnabled = canFwd
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        super.onDestroy()
    }
}
