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

    /** Menyalin URI yang di-drag/dipilih ke cache aplikasi agar selalu bisa dibuka di PdfActivity & AI tanpa mental ke ELITA. */
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

    /** Tangkap PDF jika dibuka dari luar ("Buka dengan" / Share ke aplikasi). */
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

    /** Pasang penerima Drag & Drop PDF di seluruh layar aplikasi. */
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
                                // Langsung tawarkan buka di pembaca PDF internal atau ringkas dengan AI
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

    /** Kartu area Drag & Drop PDF agar user bisa langsung tarik PDF ke aplikasi tanpa lewat ELITA. */
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
                text = "$e$l"
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
        status.text = if (err.isNotEmpty()) err else if (ls >
