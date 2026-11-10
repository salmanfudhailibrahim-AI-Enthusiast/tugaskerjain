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

/**
 * Pembaca PDF modern di dalam aplikasi:
 * Dilengkapi Drag & Drop PDF langsung ke layar aplikasi (tanpa mental ke ELITA),
 * Zoom In, Zoom Out, Fit to Screen, pinch-to-zoom, render tajam, dan tombol AI (Ringkas & Soal).
 */
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
            set
