package id.pemantau.tugas

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import kotlin.concurrent.thread

/** Halaman masuk akun kampus (Google). Juga menerima kembalian dari browser (id.pemantau.tugas:/oauth2redirect). */
class GoogleActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private var msg = ""
    private var showSetup = false

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
            ui.post { msg = if (err == null) "✅ Berhasil masuk. Kembali ke aplikasi, lalu muat ulang daftar materi." else "Gagal: $err"; draw() }
        }
    }

    private fun copy(label: String, s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, s))
        Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
    }

    private fun go(raw: String) {
        var e = raw.trim()
        if (e.isNotEmpty() && !e.contains("@")) e += "@" + GoogleAuth.DOMAIN
        if (e.isNotEmpty() && !e.endsWith("@" + GoogleAuth.DOMAIN, ignoreCase = true)) { msg = "Gunakan akun kampus yang berakhiran @${GoogleAuth.DOMAIN}."; draw(); return }
        if (GoogleAuth.clientId(this).isBlank()) { msg = "Client ID Google belum diatur. Lengkapi bagian Pengaturan awal di bawah."; showSetup = true; draw(); return }
        prefs().edit().putString("gHint", e).apply()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GoogleAuth.authUrl(this, e))))
    }

    private fun draw() {
        box.removeAllViews()
        box.addView(tv("AKUN KAMPUS", 11f, Pal.cyan, true).apply { letterSpacing = 0.25f })
        box.addView(tv("Masuk dengan Google", 28f, Pal.text, true).apply { setPadding(0, dp(6), 0, dp(8)) })
        box.addView(tv("Materi dosen di Google Drive hanya bisa dibuka oleh akun @${GoogleAuth.DOMAIN}. Aplikasi hanya meminta izin baca, tidak bisa mengubah atau menghapus file.", 14f, Pal.mute))

        if (GoogleAuth.signedIn(this)) {
            box.addView(card().apply {
                addView(tv("✅ Terhubung", 16f, Pal.green, true))
                addView(tv(GoogleAuth.email(this@GoogleActivity).ifBlank { "akun kampus" }, 13f, Pal.mute))
                addView(row(ghost("Keluar", Pal.red) { GoogleAuth.signOut(this@GoogleActivity); msg = ""; draw() }))
                if (msg.isNotEmpty()) addView(tv(msg, 13f, if (msg.startsWith("Gagal")) Pal.red else Pal.amber).apply { setPadding(0, dp(10), 0, 0) })
            })
        } else {
            val email = field("nama@${GoogleAuth.DOMAIN}").apply {
                setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                setText(prefs().str("gHint"))
            }
            box.addView(card().apply {
                addView(tv("Masuk", 20f, Pal.text, true))
                addView(tv("Gunakan akun kampusmu", 13f, Pal.mute).apply { setPadding(0, dp(2), 0, dp(12)) })
                addView(email)
                addView(row(gpill("Lanjut") { go(email.text.toString()) }.apply { textSize = 15f; setPadding(0, dp(14), 0, dp(14)) }))
                addView(tv("Kata sandi diketik di halaman resmi Google yang terbuka di browser, bukan di aplikasi ini.", 11f, Pal.mute).apply { setPadding(0, dp(10), 0, 0) })
                if (msg.isNotEmpty()) addView(tv(msg, 13f, if (msg.startsWith("Gagal") || msg.startsWith("Gunakan")) Pal.red else Pal.amber).apply { setPadding(0, dp(10), 0, 0) })
            })
        }

        box.addView(card().apply {
            addView(label("Tanpa login (tanpa setup)"))
            addView(tv("• Buka file di aplikasi Google Drive → ⋮ → Kirim salinan → pilih Tugas Kampus. PDF langsung terbuka dan bisa diringkas.\n• Atau di AI Studio pakai tombol \"PDF dari HP\"; pemilih file Android menampilkan Google Drive-mu.", 13f, Pal.text))
        })

        if (showSetup || GoogleAuth.clientId(this).isBlank()) {
            val cidField = field("xxxx.apps.googleusercontent.com").apply { setText(prefs().str("gClient")); setSingleLine() }
            val sha = try { GoogleAuth.sha1(this) } catch (e: Exception) { "(tidak terbaca)" }
            box.addView(card().apply {
                addView(label("Pengaturan awal (sekali saja)"))
                addView(tv("1. Buka Google Cloud Console, buat proyek baru.\n2. APIs & Services → Library → aktifkan \"Google Drive API\".\n3. OAuth consent screen → External → isi nama aplikasi → tambahkan emailmu di \"Test users\".\n4. Credentials → Create credentials → OAuth client ID → tipe Android. Isi Package name dan SHA-1 persis seperti di bawah. Di Advanced settings, aktifkan \"Enable custom URI scheme\".\n5. Salin Client ID, tempel di bawah, lalu Simpan.", 13f, Pal.text))
                addView(row(ghost("Buka Google Cloud Console") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/apis/credentials"))) }))
                addView(label("Package name").apply { setPadding(0, dp(14), 0, dp(4)) })
                addView(tv(packageName, 13f, Pal.cyan).apply { setTextIsSelectable(true) })
                addView(label("SHA-1 (milik aplikasi ini)").apply { setPadding(0, dp(12), 0, dp(4)) })
                addView(tv(sha, 12f, Pal.cyan).apply { setTextIsSelectable(true) })
                addView(row(ghost("Salin SHA-1") { copy("sha1", sha) }, ghost("Salin package") { copy("package", packageName) }))
                addView(label("Client ID").apply { setPadding(0, dp(14), 0, dp(4)) })
                addView(cidField)
                addView(row(gpill("Simpan Client ID") {
                    prefs().edit().putString("gClient", cidField.text.toString().trim()).apply()
                    msg = if (cidField.text.isNotBlank()) "Client ID tersimpan. Sekarang masukkan email kampus lalu tekan Lanjut." else "Client ID masih kosong."
                    showSetup = false; draw()
                }))
            })
        } else {
            box.addView(ghost("⚙ Pengaturan awal / ubah Client ID") { showSetup = true; draw() }.apply { setPadding(0, dp(12), 0, dp(12)) }, LinearLayout.LayoutParams(-1, -2))
        }
    }
}
