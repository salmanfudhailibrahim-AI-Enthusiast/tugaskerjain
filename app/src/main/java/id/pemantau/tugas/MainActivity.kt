package id.pemantau.tugas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var stats: LinearLayout
    private lateinit var tabViews: List<TextView>
    private val ui = Handler(Looper.getMainLooper())
    private var fromLogin = false
    private var tab = 0
    private var built = false
    private val picked = mutableListOf<Att>()
    private var pickedInfo: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Pal.blue
        prefs().let { if (!it.contains("baseUrl")) it.edit().putString("baseUrl", "https://elita.untidar.ac.id").putString("ssoUrl", "https://sso.untidar.ac.id/Home/Login").apply() }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (prefs().getBoolean("onboarded", false)) buildMain() else onboarding()
    }

    override fun onResume() {
        super.onResume()
        if (!built) return
        if (fromLogin) { fromLogin = false; sync() } else render()
    }

    /* ---------------- Lampiran PDF ---------------- */
    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if (req != 77 || res != RESULT_OK || d == null) return
        val uris = ArrayList<Uri>(); val cd = d.clipData
        if (cd != null) for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri else d.data?.let { uris += it }
        uris.forEach { u ->
            val nm = contentResolver.query(u, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
            } ?: "PDF"
            picked += Att(nm, uri = u)
        }
        refreshInfo()
    }

    private fun refreshInfo() { pickedInfo?.text = if (picked.isEmpty()) "Belum ada PDF dilampirkan" else picked.joinToString("\n") { "📄 " + it.name } }

    private fun pickPdf() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 77)

    private fun campusPdfs(cid: Long) {
        Toast.makeText(this, "Mengambil daftar berkas...", Toast.LENGTH_SHORT).show()
        thread {
            val l = try { Campus.coursePdfs(this, cid) } catch (e: Exception) { ui.post { Toast.makeText(this, "Gagal: ${e.message}", Toast.LENGTH_LONG).show() }; return@thread }
            ui.post {
                if (l.isEmpty()) { Toast.makeText(this, "Tidak ada berkas materi ditemukan. Pakai 'Dari HP' atau tempel teks.", Toast.LENGTH_LONG).show(); return@post }
                val on = BooleanArray(l.size)
                AlertDialog.Builder(this).setTitle("Pilih berkas materi")
                    .setMultiChoiceItems(l.map { it.name }.toTypedArray(), on) { _, i, v -> on[i] = v }
                    .setPositiveButton("Pilih") { _, _ -> l.forEachIndexed { i, a -> if (on[i] && picked.none { it.url == a.url }) picked += a }; refreshInfo() }
                    .setNegativeButton("Batal", null).show()
            }
        }
    }

    /** Kotak lampiran PDF: pilih dari HP, dari web kampus (jika mata kuliah diketahui), atau kosongkan. */
    private fun attachBox(cid: Long): LinearLayout {
        picked.clear()
        val info = tv("", 12f, Pal.mute).apply { setPadding(0, dp(8), 0, 0) }
        pickedInfo = info; refreshInfo()
        val btns = mutableListOf<TextView>(pill("📎 Dari HP", Pal.soft, Pal.ink) { pickPdf() })
        if (cid != 0L) btns += pill("🏫 Dari kampus", Pal.soft, Pal.ink) { campusPdfs(cid) }
        btns += pill("✕", Pal.soft, Pal.red) { picked.clear(); refreshInfo() }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(row(*btns.toTypedArray())); addView(info) }
    }

    /* ---------------- Layar sambutan (pertama kali dibuka) ---------------- */
    private fun onboarding() {
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.blue, Pal.violet))
        val key = EditText(this).apply {
            hint = "Tempel API key Anthropic kamu (sk-ant-...)"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Pal.ink); setHintTextColor(0xFF8A93A6.toInt())
            background = shape(Color.WHITE, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        fun finish(save: Boolean) {
            val e = prefs().edit().putBoolean("onboarded", true)
            if (save && key.text.isNotBlank()) e.putString("apiKey", key.text.toString().trim())
            e.apply(); buildMain()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = bg; setPadding(dp(24), dp(72), dp(24), dp(24))
            addView(tv("📚", 48f))
            addView(tv("Selamat datang di Tugas Kampus", 28f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, dp(8)) })
            addView(tv("Pantau deadline, ringkas materi tiap mata kuliah, dan minta AI menyusun draf jawaban tugasmu.", 15f, 0xE6FFFFFF.toInt()))
            addView(tv("1. Login kampus (SSO) dari beranda\n2. Masukkan API key milikmu sendiri di bawah. Disimpan hanya di HP ini.\n3. Tekan Sinkron, lalu ringkas atau kerjakan dengan AI", 14f, Color.WHITE).apply { setPadding(0, dp(20), 0, dp(20)) })
            addView(key, LinearLayout.LayoutParams(-1, -2))
            addView(tv("Biaya pemakaian AI ditanggung API key-mu sendiri.", 12f, 0xCCFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, dp(16)) })
            addView(pill("Simpan & mulai", Color.WHITE, Pal.blue) { finish(true) }, LinearLayout.LayoutParams(-1, -2))
            addView(pill("Dapatkan API key", tint(Color.WHITE, 45), Color.WHITE) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys"))) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(pill("Lewati dulu", Color.TRANSPARENT, Color.WHITE) { finish(false) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Pal.blue); isFillViewport = true; addView(box) })
    }

    /* ---------------- Beranda ---------------- */
    private fun round(s: String, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = shape(tint(Color.WHITE, 45), 20); setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) }
    }

    private fun buildMain() {
        built = true
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Pal.blue, Pal.violet)).apply {
            val r = dp(28).toFloat(); cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = g; setPadding(dp(20), dp(24), dp(20), dp(20)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Tugas Kampus", 26f, Color.WHITE, true))
            addView(tv("Pantau · Ringkas · Kerjakan", 12f, 0xCCFFFFFF.toInt()))
        }
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(round("↻") { sync() })
        top.addView(round("🔐") { fromLogin = true; startActivity(Intent(this, LoginActivity::class.java)) })
        top.addView(round("⚙") { settings() })
        head.addView(top)
        stats = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0) }
        head.addView(stats)
        status = tv("", 12f, 0xCCFFFFFF.toInt()).apply { setPadding(0, dp(10), 0, 0) }
        head.addView(status)

        val seg = LinearLayout(this).apply { background = shape(Pal.soft, 22); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        tabViews = listOf("Tugas", "Mata kuliah").mapIndexed { i, s ->
            TextView(this).apply { text = s; gravity = Gravity.CENTER; textSize = 14f; setPadding(0, dp(10), 0, dp(10)); setOnClickListener { tab = i; render() } }
        }
        tabViews.forEach { seg.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.bg)
            addView(head)
            addView(seg, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(16), dp(16), dp(4)) })
            addView(ScrollView(this@MainActivity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        Sync.periodic(this)
        render()
    }

    private fun sync() { status.text = "Menyinkronkan..."; thread { Sync.run(this); ui.post { render() } } }

    private fun stat(n: Int, label: String, c: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = shape(tint(Color.WHITE, 40), 16); setPadding(0, dp(10), 0, dp(10))
        addView(tv("$n", 22f, c, true).apply { gravity = Gravity.CENTER })
        addView(tv(label, 11f, Color.WHITE).apply { gravity = Gravity.CENTER })
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
    }

    private fun render() {
        val p = prefs(); val err = p.str("error"); val ls = p.getLong("lastSync", 0)
        status.text = if (err.isNotEmpty()) err else if (ls > 0) "Terakhir dicek " + SimpleDateFormat("HH:mm", Locale.forLanguageTag("id-ID")).format(Date(ls)) else "Tekan 🔐 untuk login kampus (atau isi token di ⚙), lalu ↻ Sinkron."
        status.setTextColor(if (err.isNotEmpty()) 0xFFFFD6D6.toInt() else 0xCCFFFFFF.toInt())
        val done = p.getStringSet("done", emptySet())!!; val now = System.currentTimeMillis()
        val tasks = loadTasks(this)
        val active = tasks.filter { it.id !in done }
        stats.removeAllViews()
        stats.addView(stat(active.size, "Aktif", Color.WHITE))
        stats.addView(stat(active.count { it.due >= now && it.due - now < 86400000L }, "< 24 jam", 0xFFFFE08A.toInt()))
        stats.addView(stat(active.count { it.due < now }, "Terlambat", 0xFFFFB4B4.toInt()))
        tabViews.forEachIndexed { i, t ->
            val on = i == tab
            t.background = if (on) shape(Color.WHITE, 18) else null
            t.setTextColor(if (on) Pal.ink else Pal.mute); t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        list.removeAllViews()
        if (tab == 0) renderTasks(tasks, done, now) else renderCourses(tasks)
    }

    private fun empty(emoji: String, msg: String) {
        list.addView(tv(emoji, 44f).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(8)) }, LinearLayout.LayoutParams(-1, -2))
        list.addView(tv(msg, 14f, Pal.mute).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderTasks(all: List<Task>, done: Set<String>, now: Long) {
        val tasks = all.sortedBy { it.id in done }
        if (tasks.isEmpty()) { empty("🎉", "Belum ada tugas aktif."); return }
        tasks.forEach { list.addView(row(it, it.id in done, now)) }
    }

    private fun row(t: Task, isDone: Boolean, now: Long): android.view.View {
        val late = t.due < now; val soon = !late && t.due - now < 86400000L
        val (label, col) = when {
            isDone -> "Selesai" to Pal.green
            late -> "Terlambat" to Pal.red
            soon -> ("Sisa " + left(t.due - now)) to Pal.amber
            else -> (left(t.due - now) + " lagi") to Pal.blue
        }
        val fmt = SimpleDateFormat("EEEE, d MMM HH:mm", Locale.forLanguageTag("id-ID"))
        return card().apply {
            alpha = if (isDone) .6f else 1f
            addView(chip(label, col), LinearLayout.LayoutParams(-2, -2))
            addView(tv(t.title, 16f, Pal.ink, true).apply { setPadding(0, dp(8), 0, 0) })
            addView(tv(t.course, 12f, Pal.mute))
            addView(tv("⏰ " + fmt.format(Date(t.due)), 13f, col).apply { setPadding(0, dp(4), 0, 0) })
            addView(row(pill("✨ Ringkas", tint(Pal.blue, 28), Pal.blue) { ai(t, false) }, pill("🤖 Kerjakan dengan AI", Pal.blue, Color.WHITE) { ai(t, true) }))
            addView(row(pill("Buka halaman", Pal.soft, Pal.ink) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) },
                pill(if (isDone) "Batal selesai" else "✓ Tandai selesai", Pal.soft, Pal.ink) { toggleDone(t.id) }))
        }
    }

    /* ---------------- Tab Mata kuliah ---------------- */
    private fun renderCourses(tasks: List<Task>) {
        list.addView(card().apply {
            addView(tv("📋 Materi sendiri", 16f, Pal.ink, true))
            addView(tv("Lampirkan PDF dari HP atau tempel teks catatan, AI akan meringkas atau membuat soal latihan.", 12f, Pal.mute))
            addView(row(pill("Ringkas", tint(Pal.blue, 28), Pal.blue) { courseDialog("", 0, false) }, pill("Latihan soal", tint(Pal.violet, 28), Pal.violet) { courseDialog("", 0, true) }))
        })
        val cs = tasks.filter { it.course.isNotBlank() }.groupBy { it.course }
        if (cs.isEmpty()) { empty("📚", "Mata kuliah muncul setelah sinkron tugas."); return }
        cs.forEach { (name, ts) ->
            val cid = ts.firstOrNull { it.courseId != 0L }?.courseId ?: 0L
            list.addView(card().apply {
                addView(tv(name, 16f, Pal.ink, true))
                addView(tv("${ts.size} tugas aktif", 12f, Pal.mute))
                addView(row(pill("📚 Ringkas materi", Pal.blue, Color.WHITE) { courseDialog(name, cid, false) }, pill("📝 Latihan soal", Pal.violet, Color.WHITE) { courseDialog(name, cid, true) }))
            })
        }
    }

    private fun courseDialog(course: String, cid: Long, quiz: Boolean) {
        val topic = EditText(this).apply { hint = "Topik/pertemuan (opsional), mis. Pertemuan 5" }
        val paste = EditText(this).apply { hint = "Tempel materi sendiri (opsional)"; minLines = 4; gravity = Gravity.TOP }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(topic); addView(paste); addView(attachBox(cid)) }
        val title = (if (quiz) "Latihan soal" else "Ringkas materi") + if (course.isBlank()) "" else ": $course"
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Mulai") { _, _ -> runCourse(title, course, cid, quiz, topic.text.toString(), paste.text.toString(), picked.toList()) }
            .setNegativeButton("Batal", null).show()
    }

    private fun runCourse(title: String, course: String, cid: Long, quiz: Boolean, topic: String, pasted: String, atts: List<Att>) {
        val dlg = AlertDialog.Builder(this).setTitle(title).setMessage("Mengambil materi dan menyusun hasil...").show()
        thread {
            val out = try { AI.course(this, course, cid, quiz, topic, pasted, atts) } catch (e: Exception) { "Gagal: ${e.message}" }
            ui.post { dlg.dismiss(); showResult(title, out) }
        }
    }

    /* ---------------- Aksi tugas ---------------- */
    private fun toggleDone(id: String) {
        val p = prefs(); val d = p.getStringSet("done", emptySet())!!.toMutableSet()
        if (!d.add(id)) d.remove(id)
        p.edit().putStringSet("done", d).apply(); render()
    }

    private fun ai(t: Task, solve: Boolean) {
        if (!solve) return runAi(t, false, "")
        val et = EditText(this).apply { hint = "Instruksi tambahan (opsional), misal: maksimal 500 kata, bahasa formal" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(et); addView(attachBox(t.courseId)) }
        AlertDialog.Builder(this).setTitle("Kerjakan dengan AI").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Mulai") { _, _ -> runAi(t, true, et.text.toString(), picked.toList()) }.setNegativeButton("Batal", null).show()
    }

    private fun runAi(t: Task, solve: Boolean, extra: String, atts: List<Att> = emptyList()) {
        val dlg = AlertDialog.Builder(this).setTitle(t.title).setMessage(if (solve) "AI sedang mengerjakan tugas..." else "Meringkas...").show()
        thread {
            val out = try { AI.run(this, t, solve, extra, atts) } catch (e: Exception) { "Gagal: ${e.message}" }
            ui.post { dlg.dismiss(); showResult(if (solve) "Draf jawaban" else "Ringkasan", out, if (solve) "Tinjau dan edit dulu, lalu kumpulkan sendiri lewat tombol Buka halaman." else "") }
        }
    }

    private fun showResult(title: String, out: String, note: String = "") {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        if (note.isNotEmpty()) col.addView(tv(note, 12f, Pal.amber).apply { setPadding(0, 0, 0, dp(8)) })
        col.addView(tv(out, 15f).apply { setTextIsSelectable(true) })
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("Salin") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("hasil", out))
                Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Bagikan") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out), null)) }
            .setNegativeButton("Tutup", null).show()
    }

    /* ---------------- Pengaturan ---------------- */
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
        val key = f("API key Anthropic milikmu", "apiKey").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
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
