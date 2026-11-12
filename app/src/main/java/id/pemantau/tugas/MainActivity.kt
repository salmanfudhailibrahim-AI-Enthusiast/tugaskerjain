package id.pemantau.tugas

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
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

    // ---- AI Studio ----
    private var sMode = 0      // 0 ringkas, 1 latihan soal
    private var sCourse = 0    // 0 = tanpa mata kuliah, n = urutan ke-n di daftar
    private var sTopic = ""
    private var sPaste = ""
    private var sPdfs: List<Att> = emptyList()
    private val sOn = mutableSetOf<Int>()
    private var sBusy = false
    private val picked = mutableListOf<Att>()

    // ---- Kumpulkan tugas ----
    private val subFiles = mutableListOf<Att>()
    private var subInfo: TextView? = null
    private var subDialogShowing = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs().let {
            if (!it.contains("baseUrl")) {
                it.edit()
                    .putString("baseUrl", "https://elita.untidar.ac.id")
                    .putString("ssoUrl", "https://sso.untidar.ac.id/Home/Login")
                    .apply()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (prefs().getBoolean("onboarded", false)) buildMain() else onboarding()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!built) return
        if (fromLogin) { fromLogin = false; sync() } else render()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun nameOf(u: Uri): String = contentResolver.query(u, null, null, null, null)?.use { cur ->
        val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
    } ?: u.lastPathSegment?.substringAfterLast('/') ?: "berkas.pdf"

    private fun cacheUriToAtt(u: Uri): Att? {
        return try {
            val rawName = nameOf(u)
            val safeName = if (rawName.endsWith(".pdf", true)) rawName else "$rawName.pdf"
            val cleanName = safeName.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            val outFile = File(cacheDir, "drop_${System.currentTimeMillis()}_$cleanName")
            contentResolver.openInputStream(u)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Att(safeName, uri = Uri.fromFile(outFile))
        } catch (e: Exception) {
            Att(nameOf(u), uri = u)
        }
    }

    private fun handleIncomingIntent(d: Intent?) {
        if (d == null) return
        val u: Uri? = when (d.action) {
            Intent.ACTION_VIEW -> d.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) d.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") d.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (u != null) {
            thread {
                val att = cacheUriToAtt(u) ?: return@thread
                ui.post {
                    if (att !in picked) picked += att
                    if (built) render()
                    openPdf(att)
                }
            }
        }
    }

    private fun attachDragAndDrop(targetView: View) {
        targetView.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    if (built) {
                        status.text = "📥 Lepaskan file PDF di sini untuk memasukkan ke aplikasi..."
                        status.setTextColor(Pal.cyan)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    if (built) render()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val perm = if (Build.VERSION.SDK_INT >= 24) requestDragAndDropPermissions(event) else null
                    val cd = event.clipData
                    val uris = mutableListOf<Uri>()
                    if (cd != null) {
                        for (i in 0 until cd.itemCount) {
                            cd.getItemAt(i).uri?.let { uris += it }
                        }
                    }
                    if (uris.isEmpty()) {
                        perm?.release()
                        toast("Bukan file PDF yang bisa di-drop. Pastikan menarik file PDF.")
                        if (built) render()
                        return@setOnDragListener false
                    }
                    thread {
                        val droppedAtts = uris.mapNotNull { cacheUriToAtt(it) }
                        perm?.release()
                        ui.post {
                            if (droppedAtts.isEmpty()) {
                                toast("Gagal membaca PDF yang di-drop.")
                                return@post
                            }
                            if (subDialogShowing) {
                                subFiles.addAll(droppedAtts)
                                refreshSub()
                                toast("${droppedAtts.size} file ditambahkan ke pengumpulan tugas")
                            } else if (tab == 2) {
                                picked.addAll(droppedAtts)
                                render()
                                toast("📄 ${droppedAtts.size} PDF ditambahkan ke AI Studio")
                            } else {
                                picked.addAll(droppedAtts)
                                render()
                                val first = droppedAtts.first()
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("📄 ${first.name}")
                                    .setMessage("PDF berhasil dimasukkan ke aplikasi.")
                                    .setPositiveButton("📖 Baca PDF di Aplikasi") { _, _ -> openPdf(first) }
                                    .setNeutralButton("✨ Ringkas / Buat Soal AI") { _, _ ->
                                        tab = 2
                                        render()
                                    }
                                    .setNegativeButton("Tutup", null)
                                    .show()
                            }
                        }
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (built) render()
                    true
                }
                else -> true
            }
        }
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if ((req != 77 && req != 78 && req != 79) || res != RESULT_OK || d == null) return
        val uris = ArrayList<Uri>(); val cd = d.clipData
        if (cd != null) for (i in 0 until cd.itemCount) uris += cd.getItemAt(i).uri else d.data?.let { uris += it }
        thread {
            val cached = uris.mapNotNull { cacheUriToAtt(it) }
            ui.post {
                if (req == 79) {
                    cached.firstOrNull()?.let {
                        if (it !in picked) picked += it
                        render()
                        openPdf(it)
                    }
                    return@post
                }
                cached.forEach { att -> if (req == 77) picked += att else subFiles += att }
                if (req == 77) render() else refreshSub()
            }
        }
    }

    private fun pickPdf() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/pdf").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 77)

    private fun pickPdfToRead() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/pdf"), 79)

    private fun pickAny() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 78)

    private fun refreshSub() { subInfo?.text = if (subFiles.isEmpty()) "Belum ada file dilampirkan (bisa juga drag & drop ke sini)" else subFiles.joinToString("\n") { "📄 " + it.name } }

    private fun openPdf(a: Att) {
        val i = Intent(this, PdfActivity::class.java).putExtra("name", a.name)
        a.url?.let { i.putExtra("url", it) }
        a.uri?.let {
            i.putExtra("uri", it.toString())
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(i)
    }

    private fun dropZoneCard(): View = card().apply {
        background = shape(tint(Pal.cyan, 20), 18, tint(Pal.cyan, 90))
        addView(tv("📥 Drag & Drop PDF ke Aplikasi", 14f, Pal.cyan, true))
        addView(tv("Tarik (drag) file PDF langsung ke layar ini untuk membaca di dalam aplikasi atau meringkas dengan AI tanpa membuka ELITA.", 12f, Pal.mute).apply { setPadding(0, dp(4), 0, dp(8)) })
        addView(row(
            gpill("📖 Buka PDF dari HP") { pickPdfToRead() },
            ghost("📎 Lampirkan ke AI") { tab = 2; pickPdf() }
        ))
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
            addView(tv("Pantau deadline, drag & drop PDF materi, ringkas per mata kuliah, dan buat soal latihan. Semua dari satu tempat.", 15f, Pal.mute))
            addView(card().apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(tv("1  Login kampus dari beranda (🔐 / 🔑)\n2  Masukkan API key milikmu di bawah\n3  Drag & drop PDF atau sinkron ↻, lalu buka AI Studio ✨", 14f, Pal.text))
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
    private fun iconBtn(s: String, tooltip: String = "", f: () -> Unit) = TextView(this).apply {
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
            addView(iconBtn("📄", "Buka PDF") { pickPdfToRead() })
            addView(iconBtn("↻", "Sinkronkan") { sync() })
            addView(iconBtn("🔐", "ELITA Web") { fromLogin = true; startActivity(Intent(this@MainActivity, LoginActivity::class.java)) })
            addView(iconBtn("🔑", "Google Kampus") { startActivity(Intent(this@MainActivity, GoogleActivity::class.java)) })
            addView(iconBtn("⚙", "Pengaturan") { settings() })
        }
        status = tv("", 12f, Pal.mute).apply { setPadding(dp(20), 0, dp(20), dp(6)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }

        val nav = LinearLayout(this).apply {
            background = shape(0xEE111827.toInt(), 28, Pal.edgeStrong)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        navViews = listOf("🏠" to "Tugas", "📚" to "Kuliah", "✨" to "AI Studio").mapIndexed { i, (e, l) ->
            TextView(this).apply {
                text = "$e $l"
                gravity = Gravity.CENTER
                textSize = 12.5f
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { tab = i; render() }
            }
        }
        navViews.forEach { nav.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = aurora()
            addView(top); addView(status)
            addView(ScrollView(this@MainActivity).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(16)) })
        }
        attachDragAndDrop(rootLayout)
        setContentView(rootLayout)

        Sync.periodic(this)
        render()
        thread { Updater.refresh(this, false); ui.post { render() } }
    }

    private fun sync() { status.text = "Menyinkronkan..."; thread { Sync.run(this); ui.post { render() } } }

    private fun render() {
        val p = prefs(); val err = p.str("error"); val ls = p.getLong("lastSync", 0)
        status.text = if (err.isNotEmpty()) err else if (ls > 0) "Terakhir dicek " + SimpleDateFormat("HH:mm", Locale.forLanguageTag("id-ID")).format(Date(ls)) + " · Bisa Drag & Drop PDF ke layar" else "Tekan 🔐 login ELITA, 🔑 login Google, atau Drag & Drop PDF langsung ke sini."
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

    private fun attName(a: Att): String = (if (a.url?.let { Drive.isDrive(it) } == true) "☁ " else "📄 ") + a.name

    /* ---------------- Tab Tugas ---------------- */
    private fun stat(n: Int, label: String, c: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = shape(Pal.glass, 18, Pal.edge); setPadding(0, dp(12), 0, dp(12))
        addView(tv("$n", 26f, c, true).apply { gravity = Gravity.CENTER })
        addView(tv(label, 10f, Pal.mute, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.12f })
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
    }

    private fun renderTasks() {
        val p = prefs(); val done = p.getStringSet("done", emptySet())!!; val now = System.currentTimeMillis()
        val all = loadTasks(this); val active = all.filter { it.id !in done }
        val st = LinearLayout(this)
        st.addView(stat(active.size, "AKTIF", Pal.cyan))
        st.addView(stat(active.count { it.due >= now && it.due - now < 86400000L }, "< 24 JAM", Pal.amber))
        st.addView(stat(active.count { it.due < now }, "TERLAMBAT", Pal.red))
        list.addView(st, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
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
        list.addView(dropZoneCard())
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
            addView(chip(label, col), LinearLayout.LayoutParams(-2, -2))
            addView(tv(t.title, 16f, Pal.text, true).apply { setPadding(0, dp(8), 0, 0) })
            addView(tv(t.course, 12f, Pal.mute))
            addView(tv("⏰ " + fmt.format(Date(t.due)), 13f, col).apply { setPadding(0, dp(4), 0, 0) })
            addView(row(gpill("✨ Ringkas") { runAi(t) }, ghost("📥 PDF Tugas") { pickPdfToRead() }))
            addView(row(
                ghost("📤 Kumpulkan", Pal.green) { submitDialog(t, "") },
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
        list.addView(dropZoneCard())
        list.addView(card().apply {
            addView(tv("📋 Materi sendiri", 16f, Pal.text, true))
            addView(tv("Drag & drop PDF ke aplikasi, pilih dari HP, atau tempel teks untuk diringkas / dibuatkan soal oleh AI.", 12f, Pal.mute))
            addView(row(ghost("✨ Ringkas") { openStudio(0, "", "") }, ghost("📝 Soal") { openStudio(1, "", "") }))
        })
        if (cs.isEmpty()) { empty("📚", "Belum ada mata kuliah.\nTekan 🔐 untuk login, lalu ↻ untuk sinkron."); return }
        cs.forEach { k ->
            val n = tasks.count { it.course == k.name && it.id !in done }
            list.addView(card().apply {
                addView(tv(k.name, 16f, Pal.text, true))
                addView(tv(if (n > 0) "$n tugas aktif" else "Tidak ada tugas aktif", 12f, if (n > 0) Pal.amber else Pal.mute))
                addView(row(ghost("📂 Materi") { materiDialog(k) }, ghost("✨ Ringkas") { openStudio(0, k.name, "") }, gpill("📝 Soal") { openStudio(1, k.name, "") }))
            })
        }
    }

    private fun materiDialog(k: Crs) {
        toast("Mengambil daftar materi...")
        thread {
            val l = try { Campus.coursePdfs(this, k.id) } catch (e: Exception) { ui.post { toast("Gagal: ${e.message}") }; return@thread }
            ui.post {
                val combined = l + picked
                if (combined.isEmpty()) emptyMateri(k)
                else AlertDialog.Builder(this).setTitle(k.name)
                    .setItems(combined.map { attName(it) }.toTypedArray()) { _, i -> openAtt(combined[i]) }
                    .setPositiveButton("📥 Pilih / Drag PDF") { _, _ -> pickPdfToRead() }
                    .setNegativeButton("Tutup", null).show()
            }
        }
    }

    private fun openAtt(a: Att) {
        openPdf(a)
    }

    private fun emptyMateri(k: Crs) {
        AlertDialog.Builder(this).setTitle("Masukkan PDF ke Aplikasi")
            .setMessage("Belum ada PDF otomatis di ${k.name}.\n\nKamu bisa langsung Drag & Drop file PDF ke layar aplikasi ini, atau pilih file PDF dari HP untuk langsung dibuka di aplikasi.")
            .setPositiveButton("📖 Pilih PDF dari HP") { _, _ -> pickPdfToRead() }
            .setNeutralButton("✨ Buka AI Studio") { _, _ -> openStudio(0, k.name, "") }
            .setNegativeButton("Tutup", null).show()
    }

    /* ---------------- Tab AI Studio ---------------- */
    private fun openStudio(mode: Int, course: String, taskId: String) {
        sMode = mode
        sCourse = if (course.isBlank()) 0 else courses().indexOfFirst { it.name == course } + 1
        sTopic = ""; sPaste = ""
        tab = 2; loadPdfs(); render()
    }

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
                    sPdfs = l; sOn.clear(); sOn.addAll(l.indices.filter { !Drive.isDrive(l[it].url ?: "") || GoogleAuth.signedIn(this) }.take(3))
                    if (tab == 2) render()
                }
            }
        }
    }

    private fun spinner(items: List<String>, sel: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel, false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (pos != sel) onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun renderStudio() {
        val cs = courses()
        val names = listOf("— Tanpa mata kuliah (materi sendiri) —") + cs.map { it.name }
        if (sCourse >= names.size) sCourse = 0
        if (sMode > 1) sMode = 0
        val crs = if (sCourse > 0) cs[sCourse - 1] else null

        list.addView(card().apply {
            addView(label("Mode AI"))
            val modes = listOf("✨ Ringkas materi", "📝 Latihan soal")
            addView(row(*modes.mapIndexed { i, m -> if (i == sMode) gpill(m) { } else ghost(m) { sMode = i; render() } }.toTypedArray()).apply { setPadding(0, 0, 0, 0) })
        })

        list.addView(card().apply {
            addView(label("Mata kuliah"))
            addView(spinner(names, sCourse) { pos -> sCourse = pos; loadPdfs(); render() })
        })

        list.addView(card().apply {
            addView(label("Topik / pertemuan (opsional)"))
            addView(field("mis. Pertemuan 5, Bab Normalisasi").apply { setText(sTopic); onText { sTopic = it } })
            addView(label("Tempel materi sendiri (opsional)").apply { setPadding(0, dp(12), 0, dp(6)) })
            addView(field("Teks dari slide atau catatan").apply { minLines = 4; gravity = Gravity.TOP; setText(sPaste); onText { sPaste = it } })
        })

        list.addView(card().apply {
            addView(label("Materi PDF (Drag & Drop PDF ke sini atau Pilih)"))
            if (sBusy) addView(tv("Mengambil daftar berkas materi...", 12f, Pal.mute))
            else if (crs != null && sPdfs.isEmpty()) addView(tv("Tidak ada PDF otomatis di mata kuliah ini. Silakan Drag & Drop PDF langsung ke layar ini.", 12f, Pal.mute))
            else if (crs == null) addView(tv("Drag & Drop file PDF ke layar ini, atau tekan tombol di bawah untuk melampirkan PDF.", 12f, Pal.mute))
            sPdfs.forEachIndexed { i, a ->
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(CheckBox(this@MainActivity).apply {
                        text = attName(a); setTextColor(Pal.text); textSize = 13f; isChecked = i in sOn
                        setOnCheckedChangeListener { _, v -> if (v) sOn.add(i) else sOn.remove(i) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(tv("👁", 18f).apply { setPadding(dp(10), dp(6), dp(6), dp(6)); setOnClickListener { openAtt(a) } })
                })
            }
            if (sPdfs.any { Drive.isDrive(it.url ?: "") && !GoogleAuth.signedIn(this@MainActivity) })
                addView(tv("☁ = file Google Drive. Masuk akun Google Kampus di ikon 🔑 atas agar AI dapat membaca isinya.", 11f, Pal.amber).apply { setPadding(0, dp(8), 0, 0) })
            val all = sPdfs.isNotEmpty() && sOn.size == sPdfs.size
            val btns = mutableListOf<View>(
                ghost("📎 Pilih PDF") { pickPdf() },
                ghost("📖 Buka & Baca PDF") { pickPdfToRead() }
            )
            if (sPdfs.isNotEmpty()) btns += ghost(if (all) "Kosongkan" else "Pilih semua") { if (all) sOn.clear() else sOn.addAll(sPdfs.indices); render() }
            addView(row(*btns.toTypedArray()))
            picked.forEachIndexed { i, a ->
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                    addView(tv("📄 ${a.name}", 13f, Pal.cyan).apply {
                        setOnClickListener { openAtt(a) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(tv("👁 Baca", 12f, Pal.text).apply {
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener { openAtt(a) }
                    })
                    addView(tv("✕", 14f, Pal.red).apply {
                        setPadding(dp(8), dp(4), dp(4), dp(4))
                        setOnClickListener { picked.removeAt(i); render() }
                    })
                })
            }
            addView(tv("${sOn.size + picked.size} berkas akan dibaca AI (batas total 20 MB, ±100 halaman)", 11f, Pal.mute).apply { setPadding(0, dp(8), 0, 0) })
        })

        val go = if (sMode == 0) "✨  Ringkas Sekarang" else "📝  Buat Latihan Soal"
        list.addView(gpill(go) { studioStart() }.apply { textSize = 15f; setPadding(0, dp(16), 0, dp(16)) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun studioStart() {
        val cs = courses(); val crs = if (sCourse > 0) cs.getOrNull(sCourse - 1) else null
        val atts = sPdfs.filterIndexed { i, _ -> i in sOn } + picked
        val cname = crs?.name ?: ""; val cid = crs?.id ?: 0L
        if (sPaste.isBlank() && atts.isEmpty() && cid == 0L) { toast("Pilih mata kuliah, drag & drop PDF, atau tempel materi dulu."); return }
        val quiz = sMode == 1
        runCourse((if (quiz) "Latihan soal" else "Ringkasan") + if (cname.isBlank()) "" else ": $cname", cname, cid, quiz, sTopic, sPaste, atts)
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

    /* ---------------- Kumpulkan tugas ---------------- */
    private fun submitDialog(t: Task, draft: String) {
        val why = Submit.why(this, t)
        if (why != null) {
            AlertDialog.Builder(this).setTitle("Kumpulkan tugas").setMessage(why)
                .setPositiveButton("Tutup", null).show()
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
            addView(row(ghost("📎 Lampirkan / Drag file") { pickAny() }))
            addView(info); addView(ok)
        }
        attachDragAndDrop(box)
        val d = AlertDialog.Builder(this).setTitle("Kumpulkan tugas").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kumpulkan", null).setNegativeButton("Batal", null).create()
        d.setOnDismissListener { subDialogShowing = false }
        d.setOnShowListener {
            subDialogShowing = true
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
    private fun updateCard(i: Updater.Info) = card().apply {
        background = shape(tint(Pal.green, 36), 22, tint(Pal.green, 120))
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

    /* ---------------- Pengaturan Modern ---------------- */
    private fun settings() {
        val p = prefs()
        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Moodle (ELITA)", "Canvas"))
            setSelection(if (p.str("platform") == "canvas") 1 else 0)
        }
        fun f(h: String, k: String, d: String = "") = field(h).apply { setText(p.str(k, d)); setSingleLine() }
        val url = f("URL web kampus, mis. https://elita.untidar.ac.id", "baseUrl", "https://elita.untidar.ac.id")
        val min = f("Cek otomatis tiap (menit, minimal 15)", "syncMin", "30")
        val hrs = f("Pengingat sebelum deadline (jam, pisah koma)", "hours", "24,6,1")
        val key = f("API key Anthropic milikmu (sk-ant-...)", "apiKey").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = f("Model AI", "model", "claude-sonnet-5-5")
        val token = f("Token Moodle (opsional untuk kumpulkan tugas)", "token").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val getTok = ghost("Ambil token di web kampus") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.text.toString().trim().trimEnd('/') + "/user/managetoken.php"))) }

        val gStatusText = if (GoogleAuth.signedIn(this)) "✅ Akun Google: " + GoogleAuth.email(this) else "🔑 Masuk Akun Google Kampus (Langsung Login)"
        val gd = ghost(gStatusText) { startActivity(Intent(this, GoogleActivity::class.java)) }

        val upd = ghost("Cek pembaruan (versi ${Updater.current(this)})") {
            if (Updater.slug(this).isBlank()) { toast("Build lokal: repo GitHub belum diatur."); return@ghost }
            thread {
                val n = Updater.refresh(this, false)
                ui.post { toast(if (n != null) "Versi ${n.code} tersedia" else "Sudah versi terbaru"); render() }
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)

            addView(label("Sistem Kampus (ELITA / Moodle)"))
            addView(sp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(token, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(getTok, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

            addView(label("Akun Google Kampus (Tanpa Client ID)").apply { setPadding(0, dp(14), 0, dp(4)) })
            addView(gd, LinearLayout.LayoutParams(-1, -2))

            addView(label("Kecerdasan Buatan (Anthropic AI)").apply { setPadding(0, dp(14), 0, dp(4)) })
            addView(key, LinearLayout.LayoutParams(-1, -2))
            addView(model, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

            addView(label("Sinkronisasi & Notifikasi").apply { setPadding(0, dp(14), 0, dp(4)) })
            addView(min, LinearLayout.LayoutParams(-1, -2))
            addView(hrs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

            addView(label("Aplikasi").apply { setPadding(0, dp(14), 0, dp(4)) })
            addView(upd, LinearLayout.LayoutParams(-1, -2))
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
