package id.pemantau.tugas

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
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
        val done = Button(this).apply {
            text = "Selesai login"; isAllCaps = false
            setOnClickListener { closing = true; web.loadUrl(base) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(32), 0, 0)
            addView(done)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })
    }
}
