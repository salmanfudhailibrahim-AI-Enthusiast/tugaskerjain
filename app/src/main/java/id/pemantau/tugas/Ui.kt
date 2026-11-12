package id.pemantau.tugas

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Palet warna modern bertema Burgundy (Wine / Marun elegan).
 * Bersih, sederhana, kontras tinggi, dan nyaman di mata.
 */
object Pal {
    // Backgrounds - Nuansa burgundy beludru pekat
    val bg0 = 0xFF120407.toInt()        // Latar dasar (hitam-burgundy pekat)
    val bg1 = 0xFF1B070C.toInt()        // Latar kontainer / scaffold
    val bg2 = 0xFF280B13.toInt()        // Permukaan kartu (deep burgundy velvet)
    val bg3 = 0xFF3D121F.toInt()        // Permukaan tombol/input elevated
    val cardBg = 0xFA280B13.toInt()

    // Teks
    val text = 0xFFFDF2F4.toInt()       // Teks putih hangat bersih
    val mute = 0xFFA68890.toInt()       // Teks sekunder abu-abu mauve hangat
    val faint = 0xFF6E5259.toInt()      // Garis atau hint redup

    // Aksen Utama Burgundy & Rose
    val burgundy = 0xFF881337.toInt()   // Burgundy murni (merah anggur marun)
    val burgundyDark = 0xFF540C20.toInt()
    val burgundyLight = 0xFFBE123C.toInt() // Rose-burgundy modern untuk tombol & tab aktif
    val rose = 0xFFFB7185.toInt()       // Rose lembut untuk penanda
    val wine = 0xFF6B0E27.toInt()

    // Status & kontras (kompatibilitas dengan seluruh codebase)
    val cyan = 0xFFFB7185.toInt()       // Dipetakan ke rose-burgundy
    val violet = 0xFFBE123C.toInt()     // Dipetakan ke burgundyLight
    val pink = 0xFFFB7185.toInt()       // Pink-rose aksen
    val green = 0xFF10B981.toInt()      // Hijau mint (selesai / sukses)
    val amber = 0xFFF59E0B.toInt()      // Kuning emas / peringatan / < 24 jam
    val red = 0xFFF43F5E.toInt()        // Merah rose terang untuk terlambat / error
    val gold = 0xFFFBBF24.toInt()       // Emas hangat (kombinasi klasik dengan burgundy)

    // Border & Efek Kaca
    val glass = 0x1AFB7185.toInt()      // Kaca transparan bersaput rose tipis
    val edge = 0x2EBE123C.toInt()       // Border burgundy halus
    val edgeStrong = 0x5EBE123C.toInt() // Border burgundy tegas
    val glowBurgundy = 0x44881337.toInt()
    val glowCyan = 0x33BE123C.toInt()
    val glowViolet = 0x33BE123C.toInt()
}

fun tint(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

fun Context.shape(fill: Int, r: Int = 16, stroke: Int = 0, strokeW: Int = 1) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(r).toFloat()
    if (stroke != 0) setStroke(dp(strokeW), stroke)
}

/** Gradien horizontal burgundy ke rose modern. */
fun Context.grad(r: Int = 20, c1: Int = Pal.burgundy, c2: Int = Pal.burgundyLight) =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply { cornerRadius = dp(r).toFloat() }

/** Gradien vertikal burgundy tua ke burgundy terang. */
fun Context.gradVertical(r: Int = 20, c1: Int = Pal.burgundyDark, c2: Int = Pal.burgundyLight) =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply { cornerRadius = dp(r).toFloat() }

/** Latar belakang beludru burgundy aurora yang elegan. */
fun Context.aurora(): Drawable {
    fun blob(c: Int, cx: Float, cy: Float, r: Int) = GradientDrawable().apply {
        setGradientType(GradientDrawable.RADIAL_GRADIENT)
        setGradientCenter(cx, cy)
        setGradientRadius(dp(r).toFloat())
        setColors(intArrayOf(tint(c, 90), tint(c, 0)))
    }
    return LayerDrawable(arrayOf(
        ColorDrawable(Pal.bg0),
        blob(Pal.burgundy, 0.85f, 0.05f, 380),
        blob(Pal.burgundyLight, 0.15f, 0.75f, 360),
        blob(Pal.burgundyDark, 0.5f, 0.4f, 420)
    ))
}

fun Context.tv(s: String, sz: Float = 14f, color: Int = Pal.text, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = sz; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
}

fun Context.label(s: String) = tv(s.uppercase(), 11f, Pal.rose, true).apply { letterSpacing = 0.12f; setPadding(0, 0, 0, dp(6)) }

fun Context.pill(t: String, fill: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(fg); textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
    setPadding(dp(12), dp(10), dp(12), dp(10)); background = shape(fill, 16); setOnClickListener { f() }
}

/** Tombol utama dengan gradien burgundy modern. */
fun Context.gpill(t: String, f: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
    setPadding(dp(14), dp(11), dp(14), dp(11))
    background = LayerDrawable(arrayOf(
        shape(Pal.glowBurgundy, 16),
        grad(16)
    )).apply { setLayerInset(1, dp(1), dp(1), dp(1), dp(1)) }
    setOnClickListener { f() }
}

/** Tombol sekunder dengan latar gelap burgundy halus dan border tipis. */
fun Context.ghost(t: String, fg: Int = Pal.text, f: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(fg); textSize = 12f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
    setPadding(dp(12), dp(10), dp(12), dp(10)); background = shape(Pal.bg3, 16, Pal.edge); setOnClickListener { f() }
}

/** Chip status dengan warna dinamis. */
fun Context.chip(s: String, c: Int) = TextView(this).apply {
    text = s; textSize = 11f; setTextColor(c); setTypeface(null, Typeface.BOLD)
    setPadding(dp(10), dp(4), dp(10), dp(4)); background = shape(tint(c, 38), 10)
}

/** Kartu modern bertema burgundy dengan border halus. */
fun Context.card() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = shape(Pal.bg2, 18, Pal.edge)
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
}

/** Kartu dengan border berwarna aksen. */
fun Context.glowCard(borderColor: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = shape(Pal.bg2, 18, borderColor)
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
}

fun Context.row(vararg v: View) = LinearLayout(this).apply {
    setPadding(0, dp(10), 0, 0)
    v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
}

fun Context.field(h: String) = EditText(this).apply {
    hint = h; setHintTextColor(Pal.mute); setTextColor(Pal.text); textSize = 14f
    background = shape(Pal.bg3, 14, Pal.edge); setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun EditText.onText(f: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
    })
}

/** Dialog hasil AI: teks bisa dipilih, Salin, Bagikan. [extra] = tombol tambahan di bawah teks. */
fun Activity.showText(title: String, out: String, note: String = "", extra: View? = null) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
    if (note.isNotEmpty()) col.addView(tv(note, 12f, Pal.amber).apply { setPadding(0, 0, 0, dp(8)) })
    col.addView(tv(out, 15f).apply { setTextIsSelectable(true) })
    if (extra != null) col.addView(extra, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(col) })
        .setPositiveButton("Salin") { _, _ ->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("hasil", out))
            Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("Bagikan") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out), null)) }
        .setNegativeButton("Tutup", null).show()
}
