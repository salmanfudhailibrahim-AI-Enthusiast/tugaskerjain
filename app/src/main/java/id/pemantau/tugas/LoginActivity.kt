package id.pemantau.tugas

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

/** Login lewat halaman SSO kampus. Setelah itu "Selesai login" membuka ELITA agar sesinya ikut tersimpan. */
class LoginActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val base = prefs().str("baseUrl")
        val start = prefs().str("ssoUrl", base)
        var closing = false
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    val url = u ?: return
                    if (closing && Uri.parse(url).host == Uri.parse(base).host && !url.contains("login")) {
                        CookieManager.getInstance().flush(); finish()
                    }
                }
            }
            if (start.isNotEmpty()) loadUrl(start)
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(10))
            addView(tv("Login kampus", 17f, Pal.text, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(gpill("✓ Selesai login") { closing = true; web.loadUrl(base) }.apply { setPadding(dp(16), dp(9), dp(16), dp(9)) }, LinearLayout.LayoutParams(-2, -2))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = aurora()
            addView(bar)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
}
