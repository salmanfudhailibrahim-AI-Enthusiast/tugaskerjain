package id.pemantau.tugas

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kotlin.concurrent.thread

/** Halaman login Google. Juga menerima kembalian dari browser (id.pemantau.tugas:/oauth2redirect). */
class GoogleActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private var msg = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(48), dp(20), dp(24)) }
        setContentView(ScrollView(this).apply { background = aurora(); isFillViewport = true; addView(box) })
        draw(); handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i); handle(i)
    }

    private fun handle(i: Intent?) {
        val it = i ?: return
        val d = it.data ?: return
        if (d.scheme != packageName) return
        setIntent(Intent())
        msg = "Menyelesaikan login..."; draw()
        thread {
            var err: String? = null
            try { GoogleAuth.finish(this, d) } catch (e: Exception) { err = e.message ?: "gagal" }
            ui.post { msg = if (err == null) "✅ Berhasil terhubung. Kembali ke aplikasi dan muat ulang daftar materi." else "Gagal: $err"; draw() }
        }
    }

    private fun copy(label: String, s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, s))
        Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
    }

    private fun draw() {
        box.removeAllViews()
        box.addView(tv("GOOGLE DRIVE", 11f, Pal.cyan, true).apply { letterSpacing = 0.25f })
        box.addView(tv("Hubungkan akun Google", 28f, Pal.text, true).apply { setPadding(0, dp(6), 0, dp(8)) })
        box.addView(tv("Supaya aplikasi bisa membaca PDF materi yang ditaut dosen ke Google Drive (tombol \"Lihat\" di ELITA). Aplikasi hanya meminta izin baca, tidak bisa mengubah atau menghapus file.", 14f, Pal.mute))

        box.addView(card().apply {
            if (GoogleAuth.signedIn(this@GoogleActivity)) {
                addView(tv("✅ Terhubung", 16f, Pal.green, true))
                addView(tv(GoogleAuth.email(this@GoogleActivity).ifBlank { "akun Google" }, 13f, Pal.mute))
                addView(row(ghost("Keluar dari Google", Pal.red) { GoogleAuth.signOut(this@GoogleActivity); msg = ""; draw() }))
            } else {
                addView(tv("Belum terhubung", 16f, Pal.text, true))
                addView(row(gpill("🔑  Masuk dengan Google") {
                    if (GoogleAuth.clientId(this@GoogleActivity).isBlank()) { msg = "Isi Client ID dulu di bagian Pengaturan awal di bawah."; draw() }
                    else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GoogleAuth.authUrl(this@GoogleActivity))))
                }.apply { textSize = 15f; setPadding(0, dp(15), 0, dp(15)) }))
            }
            if (msg.isNotEmpty()) addView(tv(msg, 13f, if (msg.startsWith("Gagal")) Pal.red else Pal.amber).apply { setPadding(0, dp(10), 0, 0) })
        })

        val cidField = field("xxxx.apps.googleusercontent.com").apply { setText(GoogleAuth.clientId(this@GoogleActivity)); setSingleLine() }
        val sha = try { GoogleAuth.sha1(this) } catch (e: Exception) { "(tidak terbaca)" }
        box.addView(card().apply {
            addView(label("Pengaturan awal (sekali saja)"))
            addView(tv("1. Buka Google Cloud Console, buat proyek baru.\n2. APIs & Services → Library → aktifkan \"Google Drive API\".\n3. OAuth consent screen → External → isi nama aplikasi → tambahkan emailmu di \"Test users\".\n4. Credentials → Create credentials → OAuth client ID → tipe Android. Isi Package name dan SHA-1 persis seperti di bawah. Di Advanced settings, aktifkan \"Enable custom URI scheme\".\n5. Salin Client ID, tempel di bawah, lalu Simpan.", 13f, Pal.text))
            addView(row(ghost("Buka Google Cloud Console") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/apis/credentials"))) }))
            addView(label("Package name").apply { setPadding(0, dp(14), 0, dp(4)) })
            addView(tv(packageName, 13f, Pal.cyan).apply { setTextIsSelectable(true) })
            addView(label("SHA-1").apply { setPadding(0, dp(12), 0, dp(4)) })
            addView(tv(sha, 12f, Pal.cyan).apply { setTextIsSelectable(true) })
            addView(row(ghost("Salin SHA-1") { copy("sha1", sha) }, ghost("Salin package") { copy("package", packageName) }))
            addView(label("Client ID").apply { setPadding(0, dp(14), 0, dp(4)) })
            addView(cidField)
            addView(row(gpill("Simpan Client ID") {
                prefs().edit().putString("gClient", cidField.text.toString().trim()).apply()
                msg = "Client ID tersimpan."; draw()
            }))
        })
        box.addView(tv("Tanpa login pun, file Drive yang dibagikan ke \"siapa saja yang memiliki link\" tetap bisa dibaca. Kamu juga bisa memilih PDF dari Google Drive lewat tombol \"PDF dari HP\" (pemilih file Android menampilkan Drive).", 12f, Pal.mute).apply { gravity = Gravity.START; setPadding(dp(4), 0, dp(4), 0) })
    }
}
