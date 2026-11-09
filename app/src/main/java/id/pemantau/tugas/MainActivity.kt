package id.pemantau.tugas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var fromLogin = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs().let { if (!it.contains("baseUrl")) it.edit().putString("baseUrl", "https://elita.untidar.ac.id").putString("ssoUrl", "https://sso.untidar.ac.id/Home/Login").apply() }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(40), dp(16), dp(8)) }
        root.addView(TextView(this).apply { text = "Tugas kampus"; textSize = 24f; setTypeface(null, Typeface.BOLD) })
        status = TextView(this).apply { textSize = 12f; setPadding(0, dp(2), 0, dp(8)) }
        root.addView(status)
        root.addView(bar(btn("Sinkron") { sync() }, btn("Login kampus") { fromLogin = true; startActivity(Intent(this, LoginActivity::class.java)) }, btn("Pengaturan") { settings() }))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Sync.periodic(this)
    }

    override fun onResume() {
        super.onResume()
        if (fromLogin) { fromLogin = false; sync() } else render()
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; textSize = 13f; setOnClickListener { f() } }
    private fun bar(vararg v: View) = LinearLayout(this).apply { v.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } }

    private fun sync() { status.text = "Menyinkronkan..."; thread { Sync.run(this); ui.post { render() } } }

    private fun render() {
        val p = prefs(); val err = p.str("error"); val ls = p.getLong("lastSync", 0)
        status.text = if (err.isNotEmpty()) err else if (ls > 0) "Terakhir dicek " + SimpleDateFormat("HH:mm", Locale.forLanguageTag("id-ID")).format(Date(ls)) else "Tekan Login kampus (atau isi token di Pengaturan), lalu Sinkron."
        status.setTextColor(if (err.isNotEmpty()) 0xFFC62828.toInt() else 0xFF5D6679.toInt())
        list.removeAllViews()
        val done = p.getStringSet("done", emptySet())!!; val now = System.currentTimeMillis()
        val tasks = loadTasks(this).sortedBy { it.id in done }
        if (tasks.isEmpty()) list.addView(TextView(this).apply { text = "Belum ada tugas aktif."; setPadding(0, dp(16), 0, 0) })
        tasks.forEach { list.addView(row(it, it.id in done, now)) }
    }

    private fun row(t: Task, isDone: Boolean, now: Long): View {
        val late = t.due < now; val soon = !late && t.due - now < 86400000L
        val col = if (late) 0xFFC62828.toInt() else if (soon) 0xFFB45309.toInt() else 0xFF2D5BFF.toInt()
        val fmt = SimpleDateFormat("EEEE, d MMM HH:mm", Locale.forLanguageTag("id-ID"))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)); alpha = if (isDone) .5f else 1f
            addView(TextView(context).apply { text = t.title; textSize = 16f; setTypeface(null, Typeface.BOLD) })
            addView(TextView(context).apply { text = t.course; textSize = 12f })
            addView(TextView(context).apply {
                text = fmt.format(Date(t.due)) + "  (" + (if (late) "lewat" else left(t.due - now) + " lagi") + ")"
                textSize = 14f; setTextColor(col)
            })
            addView(bar(btn("Ringkas") { ai(t, false) }, btn("Kerjakan dengan AI") { ai(t, true) }))
            addView(bar(btn("Buka halaman") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) },
                btn(if (isDone) "Batal selesai" else "Tandai selesai") { toggleDone(t.id) }))
        }
    }

    private fun toggleDone(id: String) {
        val p = prefs(); val d = p.getStringSet("done", emptySet())!!.toMutableSet()
        if (!d.add(id)) d.remove(id)
        p.edit().putStringSet("done", d).apply(); render()
    }

    private fun ai(t: Task, solve: Boolean) {
        if (!solve) return runAi(t, false, "")
        val et = EditText(this).apply { hint = "Instruksi tambahan (opsional), misal: maksimal 500 kata, bahasa formal" }
        AlertDialog.Builder(this).setTitle("Kerjakan dengan AI").setView(et)
            .setPositiveButton("Mulai") { _, _ -> runAi(t, true, et.text.toString()) }.setNegativeButton("Batal", null).show()
    }

    private fun runAi(t: Task, solve: Boolean, extra: String) {
        val dlg = AlertDialog.Builder(this).setTitle(t.title).setMessage(if (solve) "AI sedang mengerjakan tugas..." else "Meringkas...").show()
        thread {
            val out = try { AI.run(this, t, solve, extra) } catch (e: Exception) { "Gagal: ${e.message}" }
            ui.post { dlg.dismiss(); showResult(out, solve) }
        }
    }

    private fun showResult(out: String, solve: Boolean) {
        val tv = TextView(this).apply { text = out; setTextIsSelectable(true); setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val d = AlertDialog.Builder(this).setTitle(if (solve) "Draf jawaban" else "Ringkasan")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Salin") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("hasil", out))
                Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Bagikan") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out), null)) }
            .setNegativeButton("Tutup", null)
        if (solve) d.setMessage("Tinjau dan edit dulu, lalu kumpulkan sendiri lewat tombol Buka halaman.")
        d.show()
    }

    private fun settings() {
        val p = prefs()
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Moodle", "Canvas"))
            setSelection(if (p.str("platform") == "canvas") 1 else 0)
        }
        fun f(h: String, k: String, d: String = "") = EditText(this).apply { hint = h; setText(p.str(k, d)); setSingleLine() }
        val url = f("URL web kampus, mis. https://elearning.kampus.ac.id", "baseUrl")
        val min = f("Cek otomatis tiap (menit, minimal 15)", "syncMin", "30")
        val hrs = f("Pengingat sebelum deadline (jam, pisah koma)", "hours", "24,6,1")
        val key = f("API key Anthropic", "apiKey").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = f("Model", "model", "claude-sonnet-5-5")
        val token = f("Token Moodle (untuk login SSO)", "token").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val getTok = Button(this).apply {
            text = "Ambil token di web kampus"; isAllCaps = false
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.text.toString().trim().trimEnd('/') + "/user/managetoken.php"))) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            listOf(sp, url, getTok, token, min, hrs, key, model).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Pengaturan").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Simpan") { _, _ ->
                p.edit().putString("platform", if (sp.selectedItemPosition == 1) "canvas" else "moodle")
                    .putString("baseUrl", url.text.toString().trim().trimEnd('/')).putString("token", token.text.toString().trim()).putString("syncMin", min.text.toString())
                    .putString("hours", hrs.text.toString()).putString("apiKey", key.text.toString().trim())
                    .putString("model", model.text.toString().ifBlank { "claude-sonnet-5-5" }).apply()
                Sync.periodic(this); sync()
            }.setNegativeButton("Batal", null).show()
    }
}
