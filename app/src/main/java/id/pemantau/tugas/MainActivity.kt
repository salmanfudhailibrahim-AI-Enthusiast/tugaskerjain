package id.pemantau.tugas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var navViews: List<TextView>
    private val ui = Handler(Looper.getMainLooper())
    private var fromLogin = false
    private var built = false
    private var tab = 0 // 0 Tugas, 1 Kuliah, 2 AI Studio
    private var fCourse = "" // filter mata kuliah di tab Tugas (kosong = semua)

    // ---- AI Studio ----
    private var sMode = 0      // 0 ringkas, 1 latihan soal, 2 kerjakan tugas
    private var sCourse = 0    // 0 = tanpa mata kuliah, n = urutan ke-n di daftar
    private var sTask = 0      // 0 = tulis sendiri, n = tugas ke-n
    private var sTopic = ""
    private var sPaste = ""
    private var sPdfs: List<Att> = emptyList()
    private val sOn = mutableSetOf<Int>()
    private var sBusy = false
    private val picked = mutableListOf<Att>()

    // ---- Kumpulkan tugas ----
    private val subFiles = mutableListOf<Att>()
    private var subInfo: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs().let { if (!it.contains("baseUrl")) it.edit().putString("baseUrl", "https://elita.untidar.ac.id").putString("ssoUrl", "https://sso.untidar.ac.id/Home/Login").apply() }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (prefs().getBoolean("onboarded", false)) buildMain() else onboarding()
    }

    override fun onResume() {
        super.onResume()
        if (!built) return
        if (fromLogin) { fromLogin = false; sync() } else render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if ((req != 77 && req != 78) || res != RESULT_OK || d == null) return
        val uris = ArrayList<Uri>(); val cd = d.clipData
        if (cd != null) for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri else d.data?.let { uris += it }
        uris.forEach { u ->
            val nm = contentResolver.query(u, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
            } ?: "berkas"
            if (req == 77) picked += Att(nm, uri = u) else subFiles += Att(nm, uri = u)
        }
        if (req == 77) render() else refreshSub()
    }

    private fun pickPdf() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 77)

    private fun pickAny() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 78)

    private fun refreshSub() { subInfo?.text = if (subFiles.isEmpty()) "Belum ada file dilampirkan" else subFiles.joinToString("\n") { "📄 " + it.name } }

    private fun openPdf(a: Att) {
        val i = Intent(this, PdfActivity::class.java).putExtra("name", a.name)
        a.url?.let { i.putExtra("url", it) }
        a.uri?.let { i.putExtra("uri", it.toString()) }
        startActivity(i)
    }

    /* ---------------- Layar sambutan ---------------- */
    private fun onboarding() {
        val key = field("Tempel API key Anthropic kamu (sk-ant-...)").apply {
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun finish(save: Boolean) {
            val e = prefs().edit().putBoolean("onboarded", true)
            if (save && key.text.isNotBlank()) e.putString("apiKey", key.text.toString().trim())
            e.apply(); buildMain()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(72), dp(24), dp(24))
            addView(tv("TUGAS KAMPUS", 12f, Pal.cyan, true).apply { letterSpacing = 0.25f })
            addView(tv("Kuliah, tapi\nlebih cerdas.", 34f, Pal.text, true).apply { setPadding(0, dp(8), 0, dp(12)) })
            addView(tv("Pantau deadline, baca PDF materi, ringkas per mata kuliah, dan minta AI menyusun draf jawaban. Semua dari satu tempat.", 15f, Pal.mute))
            addView(card().apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(tv("1  Login kampus dari beranda (🔐)\n2  Masukkan API key milikmu di bawah\n3  Sinkron ↻, lalu buka AI Studio ✨", 14f, Pal.text))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            addView(key, LinearLayout.LayoutParams(-1, -2))
            addView(tv("API key hanya disimpan di HP ini. Biaya pemakaian AI ditanggung akun API-mu sendiri.", 12f, Pal.mute).apply { setPadding(0, dp(8), 0, dp(20)) })
            addView(gpill("Simpan & mulai") { finish(true) }.apply { textSize = 15f; setPadding(0, dp(15), 0, dp(15)) }, LinearLayout.LayoutParams(-1, -2))
            addView(ghost("Dapatkan API key") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys"))) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(tv("Lewati dulu", 13f, Pal.mute).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(8)); setOnClickListener { finish(false) } }, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(ScrollView(this).apply { background = aurora(); isFillViewport = true; addView(box) })
    }

    /* ---------------- Rangka beranda ---------------- */
    private fun iconBtn(s: String, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 17f; setTextColor(Pal.text); gravity = Gravity.CENTER
        background = shape(Pal.glass, 21, Pal.edge); setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) }
    }

    private fun buildMain() {
        built = true
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(8))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("PANTAU · RINGKAS · KERJAKAN", 10f, Pal.cyan, true).apply { letterSpacing = 0.2f })
                addView(tv("Tugas Kampus", 26f, Pal.text, true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconBtn("↻") { sync() })
            addView(iconBtn("🔐") { fromLogin = true; startActivity(Intent(this@MainActivity, LoginActivity::class.java)) })
            addView(iconBtn("⚙") { settings() })
        }
        status = tv("", 12f, Pal.mute).apply { setPadding(dp(20), 0, dp(20), dp(6)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        val nav = LinearLayout(this).apply { background = shape(0xE6121830.toInt(), 28, Pal.edge); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        navViews = listOf("🏠" to "Tugas", "📚" to "Kuliah", "✨" to "AI Studio").mapIndexed { i, (e, l) ->
            TextView(this).apply { text = "$e\n$l"; gravity = Gravity.CENTER; textSize = 11f; setPadding(0, dp(8), 0, dp(8)); setOnClickListener { tab = i; render(); list.startAnimation(AlphaAnimation(0f, 1f).apply { duration = 220 }) } }
        }
        navViews.forEach { nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = aurora()
            addView(top); addView(status)
            addView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(16)) })
        })
        Sync.periodic(this)
        render()
        thread { Updater.refresh(this, false); ui.post { render() } }
    }

    private fun sync() { status.text = "Menyinkronkan..."; thread { Sync.run(this); ui.post { render() } } }

    private fun render() {
        val p = prefs(); val err = p.str("error"); val ls = p.getLong("lastSync", 0)
        status.text = if (err.isNotEmpty()) err else if (ls > 0) "Terakhir dicek " + SimpleDateFormat("HH:mm", Locale.forLanguageTag("id-ID")).format(Date(ls)) else "Tekan 🔐 untuk login kampus (atau isi token di ⚙), lalu ↻ untuk sinkron."
        status.setTextColor(if (err.isNotEmpty()) Pal.red else Pal.mute)
        navViews.forEachIndexed { i, t ->
            val on = i == tab
            t.background = if (on) grad(20) else null
            t.setTextColor(if (on) Color.WHITE else Pal.mute)
            t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        list.removeAllViews()
        Updater.pending(this)?.let { list.addView(updateCard(it)) }
        when (tab) { 0 -> renderTasks(); 1 -> renderCourses(); else -> renderStudio() }
    }

    private fun empty(emoji: String, msg: String) {
        list.addView(tv(emoji, 44f).apply { gravity = Gravity.CENTER; setPadding(0, dp(36), 0, dp(8)) }, LinearLayout.LayoutParams(-1, -2))
        list.addView(tv(msg, 14f, Pal.mute).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun courses() = loadCourses(this)

    /* ---------------- Tab Tugas ---------------- */
    private fun stat(n: Int, label: String, c: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = shape(Pal.glass, 18, Pal.edge); setPadding(0, dp(12), 0, dp(12))
        addView(tv("$n", 26f, c, true).apply { gravity = Gravity.CENTER })
        addView(tv(label, 10f, Pal.mute, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.12f })
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
    }

    /** Baris chip yang bisa digeser; dipakai untuk filter mata kuliah. */
    private fun chipRow(items: List<String>, selected: Int, onPick: (Int) -> Unit) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@MainActivity).apply {
            items.forEachIndexed { i, s ->
                val on = i == selected
                addView(TextView(this@MainActivity).apply {
                    text = s; textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(220)
                    setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (on) Color.WHITE else Pal.mute)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = if (on) grad(18) else shape(Pal.glass, 18, Pal.edge)
                    setOnClickListener { onPick(i) }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        })
    }

    private fun renderTasks() {
        val p = prefs(); val done = p.getStringSet("done", emptySet())!!; val now = System.currentTimeMillis()
        val every = loadTasks(this)
        val names = every.map { it.course }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        if (fCourse.isNotEmpty() && fCourse !in names) fCourse = ""
        val all = every.filter { fCourse.isEmpty() || it.course == fCourse }
        val active = all.filter { it.id !in done }

        if (names.size > 1) {
            val opts = listOf("Semua") + names
            list.addView(chipRow(opts, maxOf(0, opts.indexOf(fCourse))) { fCourse = if (it == 0) "" else opts[it]; render() },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        val st = LinearLayout(this)
        st.addView(stat(active.size, "AKTIF", Pal.cyan))
        st.addView(stat(active.count { it.due >= now && it.due - now < 86400000L }, "< 24 JAM", Pal.amber))
        st.addView(stat(active.count { it.due < now }, "TERLAMBAT", Pal.red))
        list.addView(st, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // Kartu sorotan: deadline paling dekat
        val next = active.filter { it.due >= now }.minByOrNull { it.due }
        if (next != null) {
            val urgent = next.due - now < 86400000L
            val col = if (urgent) Pal.amber else Pal.cyan
            val sisa = left(next.due - now)
            list.addView(glowCard(col).apply {
                addView(label("Deadline terdekat"))
                addView(tv("$sisa lagi", 30f, col, true))
                addView(tv(next.title, 15f, Pal.text, true).apply { setPadding(0, dp(4), 0, 0) })
                addView(tv(next.course, 12f, Pal.mute))
                addView(row(
                    gpill("🤖 Kerjakan") { openStudio(2, next.course, next.id) },
                    ghost("📤 Kumpulkan", Pal.green) { submitDialog(next, prefs().str("draft_" + next.id)) }))
            })
        }

        if (all.isNotEmpty()) {
            val finished = all.size - active.size
            val frac = finished.toFloat() / all.size
            list.addView(card().apply {
                addView(tv("$finished dari ${all.size} tugas selesai", 12f, Pal.mute))
                addView(LinearLayout(this@MainActivity).apply {
                    background = shape(tint(Color.WHITE, 22), 6)
                    addView(View(this@MainActivity).apply { background = grad(6) }, LinearLayout.LayoutParams(0, dp(8), frac))
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(8), 1f - frac))
                }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
            })
        }
        val tasks = all.sortedBy { it.id in done }
        if (tasks.isEmpty()) { empty("🎉", "Belum ada tugas aktif.\nTekan 🔐 untuk login kampus, lalu ↻ untuk sinkron."); return }
        tasks.forEach { list.addView(taskCard(it, it.id in done, now)) }
    }

    private fun taskCard(t: Task, isDone: Boolean, now: Long): View {
        val late = t.due < now; val soon = !late && t.due - now < 86400000L
        val (label, col) = when {
            isDone -> "SELESAI" to Pal.green
            late -> "TERLAMBAT" to Pal.red
            soon -> ("SISA " + left(t.due - now).uppercase()) to Pal.amber
            else -> (left(t.due - now).uppercase() + " LAGI") to Pal.cyan
        }
        val fmt = SimpleDateFormat("EEEE, d MMM HH:mm", Locale.forLanguageTag("id-ID"))
        return card().apply {
            alpha = if (isDone) .55f else 1f
            background = shape(Pal.glass, 24, if (isDone) Pal.edge else tint(col, 150))
            addView(chip(label, col), LinearLayout.LayoutParams(-2, -2))
            addView(tv(t.title, 16f, Pal.text, true).apply { setPadding(0, dp(8), 0, 0) })
            addView(tv(t.course, 12f, Pal.mute))
            addView(tv("⏰ " + fmt.format(Date(t.due)), 13f, col).apply { setPadding(0, dp(4), 0, 0) })
            addView(row(ghost("✨ Ringkas") { runAi(t) }, gpill("🤖 Kerjakan") { openStudio(2, t.course, t.id) }))
            addView(row(
                ghost("📤 Kumpulkan", Pal.green) { submitDialog(t, prefs().str("draft_" + t.id)) },
                ghost("🌐 Buka") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) },
                ghost(if (isDone) "↩ Batal" else "✓ Selesai") { toggleDone(t.id) }))
        }
    }

    private fun toggleDone(id: String) {
        val p = prefs(); val d = p.getStringSet("done", emptySet())!!.toMutableSet()
        if (!d.add(id)) d.remove(id)
        p.edit().putStringSet("done", d).apply(); render()
    }

    /* ---------------- Tab Kuliah ---------------- */
    private fun renderCourses() {
        val cs = courses(); val tasks = loadTasks(this); val done = prefs().getStringSet("done", emptySet())!!
        list.addView(glowCard(Pal.cyan).apply {
            addView(tv("📋 Materi sendiri", 16f, Pal.text, true))
            addView(tv("Tempel teks atau lampirkan PDF dari HP, lalu minta AI meringkas atau membuat soal.", 12f, Pal.mute))
            addView(row(ghost("✨ Ringkas") { openStudio(0, "", "") }, ghost("📝 Soal") { openStudio(1, "", "") }))
        })
        if (cs.isEmpty()) { empty("📚", "Belum ada mata kuliah.\nTekan 🔐 untuk login, lalu ↻ untuk sinkron."); return }
        cs.forEach { k ->
            val n = tasks.count { it.course == k.name && it.id !in done }
            list.addView(glowCard(if (n > 0) Pal.amber else Pal.violet).apply {
                addView(tv(k.name, 16f, Pal.text, true))
                addView(tv(if (n > 0) "$n tugas aktif" else "Tidak ada tugas aktif", 12f, if (n > 0) Pal.amber else Pal.mute))
                addView(row(ghost("📂 Materi") { materiDialog(k) }, ghost("✨ Ringkas") { openStudio(0, k.name, "") }, gpill("📝 Soal") { openStudio(1, k.name, "") }))
            })
        }
    }

    /** Daftar PDF materi satu mata kuliah; ketuk untuk membuka di pembaca PDF aplikasi. */
    private fun materiDialog(k: Crs) {
        toast("Mengambil daftar materi...")
        thread {
            val l = try { Campus.coursePdfs(this, k.id) } catch (e: Exception) { ui.post { toast("Gagal: ${e.message}") }; return@thread }
            ui.post {
                if (l.isEmpty()) toast("Tidak ada berkas materi ditemukan di mata kuliah ini.")
                else AlertDialog.Builder(this).setTitle(k.name).setItems(l.map { it.name }.toTypedArray()) { _, i -> openPdf(l[i]) }
                    .setNegativeButton("Tutup", null).show()
            }
        }
    }

    /* ---------------- Tab AI Studio ---------------- */
    private fun openStudio(mode: Int, course: String, taskId: String) {
        sMode = mode
        sCourse = if (course.isBlank()) 0 else courses().indexOfFirst { it.name == course } + 1
        sTask = 0
        if (taskId.isNotEmpty()) {
            val ts = loadTasks(this).filter { sCourse == 0 || it.course == course }
            sTask = ts.indexOfFirst { it.id == taskId } + 1
        }
        sTopic = ""; sPaste = ""; picked.clear()
        tab = 2; loadPdfs(); render()
    }

    /** Daftar PDF mata kuliah terpilih dimuat otomatis; 3 berkas pertama langsung dicentang. */
    private fun loadPdfs() {
        val crs = if (sCourse > 0) courses().getOrNull(sCourse - 1) else null
        sPdfs = emptyList(); sOn.clear()
        if (crs == null || crs.id == 0L) { sBusy = false; return }
        sBusy = true
        thread {
            val l = try { Campus.coursePdfs(this, crs.id) } catch (e: Exception) { emptyList() }
            ui.post {
                sBusy = false
                if (sCourse > 0 && courses().getOrNull(sCourse - 1)?.id == crs.id) {
                    sPdfs = l; sOn.clear(); sOn.addAll(l.indices.take(3))
                    if (tab == 2) render()
                }
            }
        }
    }

    /** Kotak pilihan bergaya modern: menampilkan nilai terpilih, ketuk untuk membuka daftar. */
    private fun selector(value: String, onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = shape(tint(Color.WHITE, 18), 16, Pal.edge)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        addView(tv(value, 14f, Pal.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(tv("▾", 16f, Pal.cyan, true))
        setOnClickListener { onClick() }
    }

    private fun pickCourse(names: List<String>) {
        AlertDialog.Builder(this).setTitle("Pilih mata kuliah")
            .setItems(names.toTypedArray()) { _, pos -> sCourse = pos; sTask = 0; loadPdfs(); render() }
            .setNegativeButton("Batal", null).show()
    }

    private fun pickTask(names: List<String>) {
        AlertDialog.Builder(this).setTitle("Pilih tugas")
            .setItems(names.toTypedArray()) { _, pos -> sTask = pos; render() }
            .setNegativeButton("Batal", null).show()
    }

    private fun renderStudio() {
        val cs = courses()
        val names = listOf("— Tanpa mata kuliah (materi sendiri) —") + cs.map { it.name }
        if (sCourse >= names.size) sCourse = 0
        val crs = if (sCourse > 0) cs[sCourse - 1] else null
        val tasks = loadTasks(this).filter { crs == null || it.course == crs.name }
        if (sTask > tasks.size) sTask = 0

        // Pilihan mode: ringkas / latihan soal / kerjakan tugas
        list.addView(glowCard(Pal.cyan).apply {
            addView(label("Mau ngapain?"))
            val modes = listOf(
                Triple("✨ Ringkas materi", "Poin utama, istilah penting, dan pertanyaan cek pemahaman", 0),
                Triple("📝 Latihan soal", "10 soal campuran lengkap dengan kunci jawaban", 1),
                Triple("🤖 Kerjakan tugas", "Draf jawaban lengkap yang bisa kamu edit lalu kumpulkan", 2))
            modes.forEach { (title, sub, idx) ->
                val isOn = idx == sMode
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = if (isOn) grad(16) else shape(Pal.glass, 16, Pal.edge)
                    addView(tv(title, 14f, if (isOn) Color.WHITE else Pal.text, true))
                    addView(tv(sub, 11f, if (isOn) 0xCCFFFFFF.toInt() else Pal.mute))
                    setOnClickListener { sMode = idx; render() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        })

        list.addView(card().apply {
            addView(label("Mata kuliah"))
            addView(selector(names[sCourse]) { pickCourse(names) })
            if (crs != null) addView(tv("Materi PDF mata kuliah ini dimuat otomatis di bawah.", 11f, Pal.mute).apply { setPadding(0, dp(8), 0, 0) })
        })

        if (sMode == 2) list.addView(card().apply {
            addView(label("Tugas yang dikerjakan"))
            val tnames = listOf("✍ Tulis soal/instruksi sendiri") + tasks.map { it.title }
            addView(selector(tnames[sTask]) { pickTask(tnames) })
        })

        list.addView(card().apply {
            if (sMode == 2) {
                if (sTask == 0) {
                    addView(label("Soal / instruksi tugas"))
                    addView(field("Tempel soal atau jelaskan tugasnya").apply { minLines = 4; gravity = Gravity.TOP; setText(sPaste); onText { sPaste = it } })
                }
                addView(label("Instruksi tambahan (opsional)").apply { setPadding(0, dp(if (sTask == 0) 12 else 0), 0, dp(6)) })
                addView(field("mis. maksimal 500 kata, bahasa formal").apply { setText(sTopic); onText { sTopic = it } })
            } else {
                addView(label("Topik / pertemuan (opsional)"))
                addView(field("mis. Pertemuan 5, Bab Normalisasi").apply { setText(sTopic); onText { sTopic = it } })
                addView(label("Tempel materi sendiri (opsional)").apply { setPadding(0, dp(12), 0, dp(6)) })
                addView(field("Teks dari slide atau catatan").apply { minLines = 4; gravity = Gravity.TOP; setText(sPaste); onText { sPaste = it } })
            }
        })

        list.addView(card().apply {
            addView(label("Materi PDF"))
            if (sBusy) addView(tv("Mengambil daftar berkas materi...", 12f, Pal.mute))
            else if (crs != null && sPdfs.isEmpty()) addView(tv("Tidak ada PDF ditemukan di mata kuliah ini.\nKamu tetap bisa menempel teks materi sendiri di atas.", 12f, Pal.mute))
            else if (crs == null) addView(tv("Pilih mata kuliah untuk memuat PDF materi otomatis, atau lampirkan dari HP.", 12f, Pal.mute))
            sPdfs.forEachIndexed { i, a ->
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(CheckBox(this@MainActivity).apply {
                        text = a.name; setTextColor(Pal.text); textSize = 13f; isChecked = i in sOn
                        setOnCheckedChangeListener { _, v -> if (v) sOn.add(i) else sOn.remove(i) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(tv("👁", 18f).apply { setPadding(dp(10), dp(6), dp(6), dp(6)); setOnClickListener { openPdf(a) } })
                })
            }
            val all = sPdfs.isNotEmpty() && sOn.size == sPdfs.size
            val btns = mutableListOf<View>(ghost("📎 PDF dari HP") { pickPdf() })
            if (sPdfs.isNotEmpty()) btns += ghost(if (all) "Kosongkan" else "Pilih semua") { if (all) sOn.clear() else sOn.addAll(sPdfs.indices); render() }
            addView(row(*btns.toTypedArray()))
            picked.forEachIndexed { i, a ->
                addView(tv("📄 ${a.name}   ✕", 12f, Pal.cyan).apply { setPadding(0, dp(8), 0, 0); setOnClickListener { picked.removeAt(i); render() } })
            }
            addView(tv("${sOn.size + picked.size} berkas akan dibaca AI (batas total 20 MB, ±100 halaman)", 11f, Pal.mute).apply { setPadding(0, dp(8), 0, 0) })
        })

        val go = when (sMode) { 0 -> "✨  Ringkas sekarang"; 1 -> "📝  Buat latihan soal"; else -> "🤖  Kerjakan dengan AI" }
        list.addView(gpill(go) { studioStart() }.apply { textSize = 15f; setPadding(0, dp(16), 0, dp(16)) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun studioStart() {
        val cs = courses(); val crs = if (sCourse > 0) cs.getOrNull(sCourse - 1) else null
        val atts = sPdfs.filterIndexed { i, _ -> i in sOn } + picked
        val cname = crs?.name ?: ""; val cid = crs?.id ?: 0L
        if (sMode == 2) {
            val tasks = loadTasks(this).filter { crs == null || it.course == crs.name }
            val t: Task
            if (sTask == 0) {
                if (sPaste.isBlank() && atts.isEmpty()) { toast("Tulis soal/instruksi atau lampirkan PDF soal dulu."); return }
                t = Task("manual", "Tugas", cname, 0L, "", sPaste, cid)
            } else {
                t = tasks.getOrNull(sTask - 1) ?: return
            }
            runSolve(t, sTopic, atts)
        } else {
            if (sPaste.isBlank() && atts.isEmpty() && cid == 0L) { toast("Pilih mata kuliah, lampirkan PDF, atau tempel materi dulu."); return }
            val quiz = sMode == 1
            runCourse((if (quiz) "Latihan soal" else "Ringkasan") + if (cname.isBlank()) "" else ": $cname", cname, cid, quiz, sTopic, sPaste, atts)
        }
    }

    /* ---------------- Menjalankan AI ---------------- */
    private fun runCourse(title: String, course: String, cid: Long, quiz: Boolean, topic: String, pasted: String, atts: List<Att>) {
        val dlg = AlertDialog.Builder(this).setTitle(title).setMessage("Membaca materi dan PDF, lalu menyusun hasil...").show()
        thread {
            val out = try { AI.course(this, course, cid, quiz, topic, pasted, atts) } catch (e: Exception) { "Gagal: ${e.message}" }
            ui.post { dlg.dismiss(); showText(title, out) }
        }
    }

    private fun runAi(t: Task) {
        val dlg = AlertDialog.Builder(this).setTitle(t.title).setMessage("Meringkas...").show()
        thread {
            val out = try { AI.run(this, t, false, "") } catch (e: Exception) { "Gagal: ${e.message}" }
            ui.post { dlg.dismiss(); showText("Ringkasan", out) }
        }
    }

    private fun runSolve(t: Task, extra: String, atts: List<Att>) {
        val dlg = AlertDialog.Builder(this).setTitle(t.title).setMessage("AI sedang mengerjakan tugas...").show()
        thread {
            var ok = true
            val out = try { AI.run(this, t, true, extra, atts) } catch (e: Exception) { ok = false; "Gagal: ${e.message}" }
            ui.post {
                dlg.dismiss()
                if (ok && t.id != "manual") prefs().edit().putString("draft_" + t.id, out).apply()
                val btn = if (ok && t.id != "manual") gpill("📤 Kumpulkan tugas ini") { submitDialog(t, out) } else null
                showText("Draf jawaban", out, if (ok) "Tinjau dan edit dulu sebelum dikumpulkan." else "", btn)
            }
        }
    }

    /* ---------------- Kumpulkan tugas ---------------- */
    private fun submitDialog(t: Task, draft: String) {
        val why = Submit.why(this, t)
        if (why != null) {
            AlertDialog.Builder(this).setTitle("Kumpulkan tugas").setMessage(why)
                .setPositiveButton("Buka halaman") { _, _ -> if (t.url.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url))) }
                .setNegativeButton("Tutup", null).show()
            return
        }
        subFiles.clear()
        val et = field("Teks jawaban (kosongkan jika hanya mengumpulkan file)").apply { minLines = 6; gravity = Gravity.TOP; setText(draft) }
        val info = tv("", 12f, Pal.mute).apply { setPadding(0, dp(8), 0, 0) }
        subInfo = info; refreshSub()
        val ok = CheckBox(this).apply { text = "Saya sudah meninjau jawaban ini dan siap mengumpulkan"; setTextColor(Pal.text); textSize = 13f }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(tv(t.title, 14f, Pal.cyan, true).apply { setPadding(0, 0, 0, dp(8)) })
            addView(et)
            addView(row(ghost("📎 Lampirkan file") { pickAny() }))
            addView(info); addView(ok)
        }
        val d = AlertDialog.Builder(this).setTitle("Kumpulkan tugas").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kumpulkan", null).setNegativeButton("Batal", null).create()
        d.setOnShowListener {
            val b = d.getButton(AlertDialog.BUTTON_POSITIVE)
            b.isEnabled = false
            ok.setOnCheckedChangeListener { _, v -> b.isEnabled = v }
            b.setOnClickListener {
                b.isEnabled = false; d.setTitle("Mengirim...")
                val files = subFiles.toList(); val txt = et.text.toString()
                thread {
                    var msg = ""; var err: String? = null
                    try { msg = Submit.send(this, t, txt, files) } catch (e: Exception) { err = e.message ?: "gagal" }
                    ui.post {
                        d.setTitle("Kumpulkan tugas")
                        if (err != null) { b.isEnabled = ok.isChecked; toast("Gagal: $err") }
                        else {
                            d.dismiss()
                            val dn = prefs().getStringSet("done", emptySet())!!.toMutableSet(); dn.add(t.id)
                            prefs().edit().putStringSet("done", dn).apply(); render()
                            AlertDialog.Builder(this).setTitle("Berhasil").setMessage(msg).setPositiveButton("OK", null).show()
                        }
                    }
                }
            }
        }
        d.show()
    }

    /* ---------------- Update aplikasi ---------------- */
    private fun updateCard(i: Updater.Info) = glowCard(Pal.green).apply {
        addView(tv("⬆️ Pembaruan tersedia (versi ${i.code})", 15f, Pal.green, true))
        if (i.notes.isNotBlank()) addView(tv(i.notes.trim().take(160), 12f, Pal.mute).apply { setPadding(0, dp(4), 0, 0) })
        addView(row(gpill("Perbarui sekarang") { startUpdate(i) }))
    }

    private fun startUpdate(i: Updater.Info) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Izinkan 'Pasang aplikasi tidak dikenal' untuk aplikasi ini, lalu tekan Perbarui lagi.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val dlg = AlertDialog.Builder(this).setTitle("Pembaruan").setMessage("Mengunduh versi ${i.code}...").setCancelable(false).show()
        thread {
            val err = try { Updater.install(this, i.url); null } catch (e: Exception) { e.message ?: "gagal" }
            ui.post { dlg.dismiss(); if (err != null) toast("Gagal memperbarui: $err") }
        }
    }

    /* ---------------- Pengaturan ---------------- */
    private fun settings() {
        val p = prefs()
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Moodle", "Canvas"))
            setSelection(if (p.str("platform") == "canvas") 1 else 0)
        }
        fun f(h: String, k: String, d: String = "") = field(h).apply { setText(p.str(k, d)); setSingleLine() }
        val url = f("URL web kampus, mis. https://elearning.kampus.ac.id", "baseUrl")
        val min = f("Cek otomatis tiap (menit, minimal 15)", "syncMin", "30")
        val hrs = f("Pengingat sebelum deadline (jam, pisah koma)", "hours", "24,6,1")
        val key = f("API key Anthropic milikmu", "apiKey").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = f("Model", "model", "claude-sonnet-5-5")
        val token = f("Token Moodle (untuk login SSO dan kumpulkan tugas)", "token").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val getTok = ghost("Ambil token di web kampus") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.text.toString().trim().trimEnd('/') + "/user/managetoken.php"))) }
        val upd = ghost("Cek pembaruan (versi ${Updater.current(this)})") {
            if (Updater.slug(this).isBlank()) { toast("Build lokal: repo GitHub belum diatur."); return@ghost }
            thread {
                val n = Updater.refresh(this, false)
                ui.post { toast(if (n != null) "Versi ${n.code} tersedia" else "Sudah versi terbaru"); render() }
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            listOf<View>(sp, url, getTok, token, min, hrs, key, model, upd).forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
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
