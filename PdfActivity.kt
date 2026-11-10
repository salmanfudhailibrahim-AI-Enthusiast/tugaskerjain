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
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Pembaca PDF di dalam aplikasi (PdfRenderer bawaan Android) + tombol Ringkas / Soal dari PDF yang sedang dibuka. */
class PdfActivity : Activity() {
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val cache = LruCache<Int, Bitmap>(6)
    private lateinit var att: Att
    private lateinit var info: TextView
    private lateinit var lv: ListView
    private lateinit var actions: LinearLayout
    private lateinit var titleView: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        var name = intent.getStringExtra("name") ?: ""
        var uri: Uri? = intent.getStringExtra("uri")?.let { Uri.parse(it) }
        val url = intent.getStringExtra("url")
        if (uri == null && url == null) {
            // Dibuka dari aplikasi lain (mis. Google Drive: Kirim salinan / Buka dengan)
            uri = sharedUri()
            if (uri != null && name.isBlank()) name = displayName(uri)
        }
        if (name.isBlank()) name = "PDF"
        att = Att(name, url, uri)
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), 0); visibility = View.GONE }
        info = tv("Membuka PDF...", 14f, Pal.mute).apply { gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0) }
        lv = ListView(this).apply {
            divider = null; dividerHeight = dp(10); setPadding(dp(12), 0, dp(12), dp(24)); clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT); visibility = View.GONE
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(10))
            titleView = tv(name, 15f, Pal.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(gpill("✨ Ringkas") { ai(false) }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            addView(ghost("📝 Soal") { ai(true) }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = aurora()
            addView(bar); addView(info, LinearLayout.LayoutParams(-1, -2)); addView(actions, LinearLayout.LayoutParams(-1, -2)); addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        load()
    }

    private fun load() {
        thread {
            try {
                val data = Docs.bytes(this, att)
                if (data.size < 5 || String(data, 0, 5, Charsets.ISO_8859_1) != "%PDF-") throw Exception("berkas ini bukan PDF")
                val f = File(cacheDir, "view.pdf"); f.writeBytes(data)
                val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(d)
                ui.post { pfd = d; renderer = r; show(r) }
            } catch (e: Exception) {
                ui.post {
                    info.text = "Gagal membuka PDF: ${e.message}"
                    val u = att.url
                    if (u != null) {
                        actions.visibility = View.VISIBLE; actions.removeAllViews()
                        actions.addView(gpill("📲  Buka di aplikasi Drive") {
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage("com.google.android.apps.docs")) } catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                        }, LinearLayout.LayoutParams(-1, -2))
                        actions.addView(ghost("📥 Pilih file ini dari Drive") {
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf"), 5)
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                        actions.addView(tv("Atau di aplikasi Google Drive: ⋮ → Kirim salinan → Tugas Kampus.", 12f, Pal.mute).apply { setPadding(0, dp(12), 0, 0); gravity = Gravity.CENTER })
                    }
                }
            }
        }
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        super.onActivityResult(req, res, d)
        if (req != 5 || res != RESULT_OK) return
        val u = d?.data ?: return
        att = Att(displayName(u), null, u)
        titleView.text = att.name
        info.text = "Membuka PDF..."; info.visibility = View.VISIBLE; lv.visibility = View.GONE; actions.visibility = View.GONE
        load()
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(): Uri? {
        intent.data?.let { return it }
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    }

    private fun displayName(u: Uri): String = contentResolver.query(u, null, null, null, null)?.use { cur ->
        val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cur.moveToFirst() && i >= 0) cur.getString(i) else null
    } ?: "PDF"

    private fun show(r: PdfRenderer) {
        info.visibility = View.GONE; lv.visibility = View.VISIBLE
        val w = resources.displayMetrics.widthPixels - dp(24)
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
