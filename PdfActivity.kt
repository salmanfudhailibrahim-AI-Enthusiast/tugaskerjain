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
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Pembaca PDF modern di dalam aplikasi:
 * Dilengkapi Zoom In, Zoom Out, Fit to Screen, pinch-to-zoom, render tajam,
 * navigasi kembali, dan tombol AI (Ringkas & Soal).
 */
class PdfActivity : Activity() {
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, Bitmap>(16)
    private lateinit var att: Att
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

    // Tingkat zoom yang tersedia
    private val zoomLevels = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.8f, 2.2f, 3.0f)
    private var zoomIndex = 1 // Default 1.0f (100% / Fit width)
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

        // Setup Detektor Cubit untuk Zoom (Pinch-to-zoom)
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

        // Header Top Bar
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
        topRow.addView(btnAiSummarize, LinearLayout.LayoutParams(-2, -2))
        topRow.addView(btnAiQuiz, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })

        // Toolbar Kontrol Zoom di baris kedua
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

        val pageHintTv = tv("Pinch layar untuk memperbesar", 11f, Pal.mute).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        zoomCluster.addView(btnZoomOut, LinearLayout.LayoutParams(-2, -2))
        zoomCluster.addView(zoomBadge, LinearLayout.LayoutParams(-2, -2))
        zoomCluster.addView(btnZoomIn, LinearLayout.LayoutParams(-2, -2))
        zoomCluster.addView(btnFit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        zoomCluster.addView(pageHintTv, LinearLayout.LayoutParams(0, -2, 1f))

        bar.addView(topRow)
        bar.addView(zoomCluster)

        // Loading & Error Info Box
        infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
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

        // PDF View Container (2D Scroll: Vertical ScrollView + HorizontalScrollView)
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

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = aurora()
            addView(bar)
            addView(infoBox, LinearLayout.LayoutParams(-1, -2))
            addView(vScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        load()
    }

    private fun load() {
        infoBox.visibility = View.VISIBLE
        loadSpinner.visibility = View.VISIBLE
        errorActions.visibility = View.GONE
        vScroll.visibility = View.GONE
        zoomCluster.visibility = View.GONE

        thread {
            try {
                val data = Docs.bytes(this, att)
                if (data.size < 5 || String(data, 0, 5, Charsets.ISO_8859_1) != "%PDF-") {
                    throw Exception("berkas ini bukan format PDF")
                }
                val f = File(cacheDir, "view.pdf")
                f.writeBytes(data)
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(d)
                ui.post {
                    pfd = d
                    renderer = r
                    displayPdf(r)
                }
            } catch (e: Exception) {
                ui.post { showError(e.message ?: "gagal membaca file") }
            }
        }
    }

    private fun showError(errMsg: String) {
        loadSpinner.visibility = View.GONE
        infoText.text = "⚠️ Gagal membuka PDF:\n$errMsg"
        infoText.setTextColor(Pal.red)
        errorActions.removeAllViews()
        errorActions.visibility = View.VISIBLE

        val u = att.url
        if (u != null) {
            errorActions.addView(gpill("📲  Buka di Aplikasi Google Drive") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage("com.google.android.apps.docs"))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                }
            }, LinearLayout.LayoutParams(-1, -2))

            errorActions.addView(ghost("📥 Pilih file ini dari Drive HP") {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"),
                    5
                )
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

            errorActions.addView(ghost("🔑 Hubungkan Akun Google Kampus") {
                startActivity(Intent(this, GoogleActivity::class.java))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

            errorActions.addView(
                tv("Tips: Jika file ada di Google Drive dosen, pastikan sudah masuk akun Google Kampus di aplikasi.", 12f, Pal.mute).apply {
                    setPadding(0, dp(14), 0, 0)
                    gravity = Gravity.CENTER
                }
            )
        }
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if (req != 5 || res != RESULT_OK) return
        val u = d?.data ?: return
        att = Att(displayName(u), null, u)
        titleView.text = att.name
        infoText.text = "Membuka PDF..."
        infoText.setTextColor(Pal.mute)
        load()
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
    } ?: "Dokumen PDF"

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
        val renderW = minOf(targetW, 2400) // Batasi resolusi render agar tidak Out of Memory

        renderGen++
        val currentGen = renderGen

        for (p in 0 until count) {
            val holder = pageHolders.getOrNull(p) ?: continue
            val iv = pageViews.getOrNull(p) ?: continue

            // Update lebar halaman di layout
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
                // Estimasi rasio A4 (1.41) sementara render selesai
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
