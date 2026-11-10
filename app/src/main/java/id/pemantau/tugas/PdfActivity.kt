package id.pemantau.tugas

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.LruCache
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class PdfActivity : Activity() {
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, Bitmap>(16)
    private lateinit var att: Att
    private lateinit var rootLayout: LinearLayout
    private lateinit var infoBox: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var loadSpinner: ProgressBar
    private lateinit var errorActions: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var zoomBadge: TextView
    private lateinit var btnZoomIn: TextView
    private lateinit var btnZoomOut: TextView
    private lateinit var btnFit: TextView
    private lateinit var zoomCluster: LinearLayout

    private lateinit var vScroll: ScrollView
    private lateinit var hScroll: HorizontalScrollView
    private lateinit var pagesLayout: LinearLayout
    private val pageViews = mutableListOf<ImageView>()
    private val pageHolders = mutableListOf<LinearLayout>()

    private val zoomLevels = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.8f, 2.2f, 3.0f)
    private var zoomIndex = 1
    private val currentZoom: Float get() = zoomLevels[zoomIndex]
    private var renderGen = 0

    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        var name = intent.getStringExtra("name") ?: ""
        var uri: Uri? = intent.getStringExtra("uri")?.let { Uri.parse(it) }
        val url = intent.getStringExtra("url")
        if (uri == null && url == null) {
            uri = sharedUri()
            if (uri != null && name.isBlank()) name = displayName(uri)
        }
        if (name.isBlank()) name = "Dokumen PDF"
        att = Att(name, url, uri)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val f = detector.scaleFactor
                if (f > 1.15f && zoomIndex < zoomLevels.size - 1) {
                    zoomIndex++
                    applyZoom()
                } else if (f < 0.85f && zoomIndex > 0) {
                    zoomIndex--
                    applyZoom()
                }
            }
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(0xF010172A.toInt(), 0)
            setPadding(dp(12), dp(36), dp(12), dp(8))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Pal.text)
            gravity = Gravity.CENTER
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { finish() }
        }

        titleView = tv(name, 14f, Pal.text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
        }

        val btnPickPdf = TextView(this).apply {
            text = "📥"
            textSize = 15f
            setTextColor(Pal.text)
            gravity = Gravity.CENTER
            background = shape(Pal.glass, 14, Pal.edge)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener { pickLocalPdf() }
        }

        val btnAiSummarize = TextView(this).apply {
            text = "✨ Ringkas"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = grad(16)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { ai(false) }
        }

        val btnAiQuiz = TextView(this).apply {
            text = "📝 Soal"
            setTextColor(Pal.text)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = shape(Pal.glass, 16, Pal.edge)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { ai(true) }
        }

        topRow.addView(btnBack, LinearLayout.LayoutParams(-2, -2))
        topRow.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(btnPickPdf, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        topRow.addView(btnAiSummarize, LinearLayout.LayoutParams(-2, -2))
        topRow.addView(btnAiQuiz, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        zoomCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Pal.glass, 16, Pal.edge)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            visibility = View.GONE
        }

        fun zoomBtn(symbol: String, onClick: () -> Unit) = TextView(this).apply {
            text = symbol
            textSize = 15f
            setTextColor(Pal.text)
            gravity = Gravity.CENTER
            background = shape(0x22FFFFFF, 12)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

        btnZoomOut = zoomBtn("➖") {
            if (zoomIndex > 0) {
                zoomIndex--
                applyZoom()
            }
        }

        zoomBadge = TextView(this).apply {
            text = "100%"
            textSize = 12f
            setTextColor(Pal.cyan)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                zoomIndex = 1
                applyZoom()
            }
        }

        btnZoomIn = zoomBtn("➕") {
            if (zoomIndex < zoomLevels.size - 1) {
                zoomIndex++
                applyZoom()
            }
        }

        btnFit = TextView(this).apply {
            text = "⤢ Pas Layar"
            textSize = 11f
            setTextColor(Pal.mute)
            gravity = Gravity.CENTER
            background = shape(Pal.glass, 12, Pal.edge)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                zoomIndex = 1
                applyZoom()
            }
        }

        val pageHintTv = tv("Bisa Drag & Drop PDF ke sini", 11f, Pal.mute).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        zoomCluster.addView(btnZoomOut, LinearLayout.LayoutParams(-2, -2))
        zoomCluster.addView(zoomBadge, LinearLayout.LayoutParams(-2, -2))
        zoomCluster.addView(btnZoomIn, LinearLayout.LayoutParams(-2, -2))
        zoomCluster.addView(btnFit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        zoomCluster.addView(pageHintTv, LinearLayout.LayoutParams(0, -2, 1f))

        bar.addView(topRow)
        bar.addView(zoomCluster)

        infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }
        loadSpinner = ProgressBar(this).apply { visibility = View.VISIBLE }
        infoText = tv("Membuka PDF...", 14f, Pal.mute).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        errorActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
            visibility = View.GONE
        }
        infoBox.addView(loadSpinner)
        infoBox.addView(infoText)
        infoBox.addView(errorActions, LinearLayout.LayoutParams(-1, -2))

        vScroll = ScrollView(this).apply {
            isFillViewport = true
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
        }

        hScroll = object : HorizontalScrollView(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }

        pagesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(36))
        }

        hScroll.addView(pagesLayout)
        vScroll.addView(hScroll)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = aurora()
            addView(bar)
            addView(infoBox, LinearLayout.LayoutParams(-1, -2))
            addView(vScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        setupDragAndDrop(rootLayout)
        setContentView(rootLayout)

        if (att.uri == null && att.url == null) {
            showDropZonePrompt("Tarik (Drag & Drop) file PDF langsung ke layar ini, atau pilih file PDF dari HP.")
        } else {
            load()
        }
    }

    private fun pickLocalPdf() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"),
            5
        )
    }

    private fun setupDragAndDrop(target: View) {
        target.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    titleView.text = "📥 Lepaskan PDF di sini..."
                    titleView.setTextColor(Pal.cyan)
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    titleView.text = att.name
                    titleView.setTextColor(Pal.text)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    titleView.setTextColor(Pal.text)
                    val perm = if (Build.VERSION.SDK_INT >= 24) requestDragAndDropPermissions(event) else null
                    val clip = event.clipData
                    val droppedUri = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).uri else null
                    if (droppedUri == null) {
                        perm?.release()
                        Toast.makeText(this, "File yang di-drag bukan PDF yang valid", Toast.LENGTH_SHORT).show()
                        return@setOnDragListener false
                    }
                    loadFromDroppedOrPickedUri(droppedUri) {
                        perm?.release()
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    titleView.text = att.name
                    titleView.setTextColor(Pal.text)
                    true
                }
                else -> true
            }
        }
    }

    private fun loadFromDroppedOrPickedUri(u: Uri, onDone: () -> Unit = {}) {
        infoBox.visibility = View.VISIBLE
        loadSpinner.visibility = View.VISIBLE
        errorActions.visibility = View.GONE
        vScroll.visibility = View.GONE
        zoomCluster.visibility = View.GONE
        infoText.text = "Memuat PDF ke dalam aplikasi..."
        infoText.setTextColor(Pal.cyan)

        thread {
            try {
                val dName = displayName(u)
                val outFile = File(cacheDir, "view.pdf")
                contentResolver.openInputStream(u)?.use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("Tidak dapat membaca sumber file PDF")

                val header = ByteArray(5)
                outFile.inputStream().use { it.read(header) }
                if (String(header, Charsets.ISO_8859_1) != "%PDF-") {
                    throw Exception("Berkas yang dimasukkan bukan format PDF")
                }

                att = Att(dName, url = null, uri = Uri.fromFile(outFile))
                openRendererFromFile(outFile, dName)
            } catch (e: Exception) {
                ui.post { showDropZonePrompt("Gagal membaca PDF: ${e.message}") }
            } finally {
                onDone()
            }
        }
    }

    private fun openRendererFromFile(f: File, docTitle: String = att.name) {
        try {
            renderer?.close()
            pfd?.close()
        } catch (_: Exception) {}

        val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(d)
        ui.post {
            titleView.text = docTitle
            pfd = d
            renderer = r
            displayPdf(r)
        }
    }

    private fun load() {
        infoBox.visibility = View.VISIBLE
        loadSpinner.visibility = View.VISIBLE
        errorActions.visibility = View.GONE
        vScroll.visibility = View.GONE
        zoomCluster.visibility = View.GONE
        infoText.text = "Membuka PDF di dalam aplikasi..."
        infoText.setTextColor(Pal.mute)

        thread {
            try {
                val data = Docs.bytes(this, att)
                if (data.size < 5 || String(data, 0, 5, Charsets.ISO_8859_1) != "%PDF-") {
                    throw Exception("File belum terunduh langsung sebagai PDF")
                }
                val f = File(cacheDir, "view.pdf")
                f.writeBytes(data)
                att = Att(att.name, url = att.url, uri = Uri.fromFile(f))
                openRendererFromFile(f, att.name)
            } catch (e: Exception) {
                ui.post {
                    showDropZonePrompt("File ini tidak bisa diambil otomatis dari server.\nSilakan langsung Drag & Drop file PDF-nya ke layar ini atau pilih dari HP.")
                }
            }
        }
    }

    private fun showDropZonePrompt(msg: String) {
        loadSpinner.visibility = View.GONE
        infoText.text = "📥 Drag & Drop PDF ke Aplikasi"
        infoText.setTextColor(Pal.cyan)
        errorActions.removeAllViews()
        errorActions.visibility = View.VISIBLE

        val dropCard = card().apply {
            background = shape(tint(Pal.cyan, 24), 20, tint(Pal.cyan, 110))
            setPadding(dp(20), dp(24), dp(20), dp(24))
            gravity = Gravity.CENTER

            addView(tv("📄", 40f).apply { gravity = Gravity.CENTER })
            addView(tv("Tarik & Lepaskan File PDF di Sini", 16f, Pal.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(6))
            })
            addView(tv(msg, 12.5f, Pal.mute).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(18))
            })
            addView(gpill("📂  Pilih File PDF dari HP / Drive") {
                pickLocalPdf()
            }.apply {
                textSize = 14f
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        errorActions.addView(dropCard, LinearLayout.LayoutParams(-1, -2))

        if (!GoogleAuth.signedIn(this)) {
            errorActions.addView(ghost("🔑 Login Akun Google (Jika File di Google Drive)") {
                startActivity(Intent(this, GoogleActivity::class.java))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if (req != 5 || res != RESULT_OK) return
        val u = d?.data ?: return
        loadFromDroppedOrPickedUri(u)
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(): Uri? {
        intent.data?.let { return it }
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
    }

    private fun displayName(u: Uri): String = contentResolver.query(u, null, null, null, null)?.use { cur ->
        val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
    } ?: u.lastPathSegment?.substringAfterLast('/') ?: "Dokumen PDF"

    private fun displayPdf(r: PdfRenderer) {
        infoBox.visibility = View.GONE
        vScroll.visibility = View.VISIBLE
        zoomCluster.visibility = View.VISIBLE

        pagesLayout.removeAllViews()
        pageViews.clear()
        pageHolders.clear()
        cache.evictAll()

        val count = r.pageCount
        for (p in 0 until count) {
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = shape(Color.WHITE, 6, tint(Color.BLACK, 40))
                elevation = dp(4).toFloat()
            }

            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                tag = p
            }

            val pageLabel = tv("Hal. ${p + 1} dari $count", 11f, 0xFF64748B.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
            }

            holder.addView(iv)
            holder.addView(pageLabel)

            pageViews.add(iv)
            pageHolders.add(holder)
            pagesLayout.addView(holder)
        }

        applyZoom()
    }

    private fun applyZoom() {
        val r = renderer ?: return
        val count = r.pageCount
        val z = currentZoom

        zoomBadge.text = "${(z * 100).toInt()}%"
        btnZoomOut.alpha = if (zoomIndex > 0) 1.0f else 0.35f
        btnZoomIn.alpha = if (zoomIndex < zoomLevels.size - 1) 1.0f else 0.35f

        val screenW = resources.displayMetrics.widthPixels
        val baseW = screenW - dp(24)
        val targetW = (baseW * z).toInt()
        val renderW = minOf(targetW, 2400)

        renderGen++
        val currentGen = renderGen

        for (p in 0 until count) {
            val holder = pageHolders.getOrNull(p) ?: continue
            val iv = pageViews.getOrNull(p) ?: continue

            holder.layoutParams = LinearLayout.LayoutParams(targetW, -2).apply {
                setMargins(0, 0, 0, dp(14))
            }

            val cacheKey = "$p@$renderW"
            val cached = cache.get(cacheKey)
            if (cached != null) {
                iv.setImageBitmap(cached)
                val targetH = (cached.height.toFloat() * targetW / cached.width).toInt()
                iv.layoutParams = LinearLayout.LayoutParams(targetW, targetH)
            } else {
                val estH = (targetW * 1.41f).toInt()
                iv.layoutParams = LinearLayout.LayoutParams(targetW, estH)

                pool.execute {
                    val bmp = try {
                        synchronized(r) {
                            val pg = r.openPage(p)
                            val h = (pg.height.toFloat() * renderW / pg.width).toInt()
                            val bm = Bitmap.createBitmap(renderW, h, Bitmap.Config.RGB_565)
                            bm.eraseColor(Color.WHITE)
                            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pg.close()
                            bm
                        }
                    } catch (e: Exception) { null }

                    if (bmp != null) {
                        cache.put(cacheKey, bmp)
                        ui.post {
                            if (renderGen == currentGen && iv.tag == p) {
                                iv.setImageBitmap(bmp)
                                val finalH = (bmp.height.toFloat() * targetW / bmp.width).toInt()
                                iv.layoutParams = LinearLayout.LayoutParams(targetW, finalH)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ai(quiz: Boolean) {
        val dlg = AlertDialog.Builder(this)
            .setTitle(att.name)
            .setMessage("AI sedang membaca halaman PDF...")
            .setCancelable(false)
            .show()

        thread {
            val out = try {
                AI.course(this, "", 0L, quiz, "", "", listOf(att))
            } catch (e: Exception) {
                "Gagal: ${e.message}"
            }
            ui.post {
                dlg.dismiss()
                showText(if (quiz) "Latihan Soal dari PDF" else "Ringkasan PDF", out)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
        try {
            renderer?.close()
            pfd?.close()
        } catch (e: Exception) { }
    }
}
