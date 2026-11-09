package id.pemantau.tugas

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Pembaca PDF futuristik di dalam aplikasi (PdfRenderer bawaan Android) + tombol Ringkas / Soal dari PDF yang sedang dibuka. */
class PdfActivity : Activity() {
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val cache = LruCache<Int, Bitmap>(6)
    private lateinit var att: Att
    private lateinit var info: TextView
    private lateinit var lv: ListView
    private lateinit var pageInfo: TextView
    private lateinit var seekBar: SeekBar
    private var totalPages = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val name = intent.getStringExtra("name") ?: "PDF"
        att = Att(name, intent.getStringExtra("url"), intent.getStringExtra("uri")?.let { Uri.parse(it) })
        info = tv("Membuka PDF...", 14f, Pal.mute).apply { gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0) }
        lv = ListView(this).apply {
            divider = null; dividerHeight = dp(10); setPadding(dp(12), 0, dp(12), dp(24)); clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT); visibility = View.GONE
        }
        pageInfo = tv("", 12f, Pal.mute).apply { gravity = Gravity.CENTER }
        seekBar = SeekBar(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && p < totalPages) lv.smoothScrollToPositionFromTop(p, 0)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(10))
            addView(tv(name, 15f, Pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(gpill("✨ Ringkas") { ai(false) }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            addView(ghost("📝 Soal") { ai(true) }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Pal.glass, 0, Pal.edge)
            addView(pageInfo)
            addView(seekBar)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = aurora()
            addView(bar); addView(info, LinearLayout.LayoutParams(-1, -2)); addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(navBar, LinearLayout.LayoutParams(-1, -2))
        })

        thread {
            try {
                val data = if (att.uri != null) (contentResolver.openInputStream(att.uri!!)?.use { it.readBytes() } ?: ByteArray(0))
                else Net.bytes(att.url!!, mapOf("Cookie" to Net.cookie(prefs().str("baseUrl"))))
                if (data.size < 5 || String(data, 0, 5, Charsets.ISO_8859_1) != "%PDF-") throw Exception("berkas ini bukan PDF")
                val f = File(cacheDir, "view.pdf"); f.writeBytes(data)
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(d)
                ui.post { pfd = d; renderer = r; totalPages = r.pageCount; seekBar.max = totalPages - 1; show(r) }
            } catch (e: Exception) {
                ui.post { info.text = "Gagal membuka PDF: ${e.message}" }
            }
        }
    }

    private fun show(r: PdfRenderer) {
        info.visibility = View.GONE; lv.visibility = View.VISIBLE
        val w = resources.displayMetrics.widthPixels - dp(24)
        pageInfo.text = "Halaman 1 / $totalPages"
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = r.pageCount
            override fun getItem(p: Int): Any = p
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup?): View {
                val iv = (v as? ImageView) ?: ImageView(this@PdfActivity).apply { setBackgroundColor(Color.WHITE) }
                iv.tag = p
                val cached = cache.get(p)
                if (cached != null) {
                    iv.setImageBitmap(cached); iv.layoutParams = AbsListView.LayoutParams(w, cached.height)
                } else {
                    iv.setImageBitmap(null); iv.layoutParams = AbsListView.LayoutParams(w, (w * 1.41f).toInt())
                    pool.execute {
                        val bmp = try {
                            synchronized(r) {
                                val pg = r.openPage(p)
                                val h = (pg.height.toFloat() * w / pg.width).toInt()
                                val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bm.eraseColor(Color.WHITE)
                                pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pg.close(); bm
                            }
                        } catch (e: Exception) { null }
                        if (bmp != null) ui.post {
                            cache.put(p, bmp)
                            if (iv.tag == p) { iv.setImageBitmap(bmp); iv.layoutParams = AbsListView.LayoutParams(w, bmp.height) }
                        }
                    }
                }
                return iv
            }
        }
        lv.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (firstVisibleItem >= 0 && firstVisibleItem < totalPages) {
                    pageInfo.text = "Halaman ${firstVisibleItem + 1} / $totalPages"
                    seekBar.progress = firstVisibleItem
                }
            }
        })
    }

    private fun ai(quiz: Boolean) {
        val dlg = AlertDialog.Builder(this).setTitle(att.name).setMessage("AI sedang membaca PDF...").show()
        thread {
            val out = try { AI.course(this, "", 0L, quiz, "", "", listOf(att)) } catch (e: Exception) { "Gagal: ${e.message}" }
            ui.post { dlg.dismiss(); showText(if (quiz) "Latihan soal" else "Ringkasan", out) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
        try { renderer?.close(); pfd?.close() } catch (e: Exception) { }
    }
}
