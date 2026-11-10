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

/** Palet warna modern dark & glassmorphism. */
object Pal {
    val bg0 = 0xFF0A0F1D.toInt()
    val bgCard = 0x1A1E293B.toInt()
    val text = 0xFFF8FAFC.toInt()
    val textSub = 0xFFCBD5E1.toInt()
    val mute = 0xFF94A3B8.toInt()
    val cyan = 0xFF22D3EE.toInt()
    val violet = 0xFF8B5CF6.toInt()
    val indigo = 0xFF6366F1.toInt()
    val pink = 0xFFF472B6.toInt()
    val green = 0xFF10B981.toInt()
    val amber = 0xFFF59E0B.toInt()
    val red = 0xFFEF4444.toInt()
    val glass = 0x1AFFFFFF
    val glassStrong = 0x2AFFFFFF
    val edge = 0x24FFFFFF
    val edgeStrong = 0x3DFFFFFF
}

fun tint(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

fun Context.shape(fill: Int, r: Int = 16, stroke: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.grad(r: Int = 20, c1: Int = Pal.cyan, c2: Int = Pal.violet) =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply { cornerRadius = dp(r).toFloat() }

fun Context.gradV(r: Int = 20, c1: Int = Pal.cyan, c2: Int = Pal.violet) =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2)).apply { cornerRadius = dp(r).toFloat() }

/** Latar gelap modern dengan cahaya aurora lembut (cyan, ungu, pink). */
fun Context.aurora(): Drawable {
    fun blob(c: Int, cx: Float, cy: Float, r: Int, alpha: Int = 85) = GradientDrawable().apply {
        setGradientType(GradientDrawable.RADIAL_GRADIENT)
        setGradientCenter(cx, cy)
        setGradientRadius(dp(r).toFloat())
        setColors(intArrayOf(tint(c, alpha), tint(c, 0)))
    }
    return LayerDrawable(arrayOf(
        ColorDrawable(Pal.bg0),
        blob(Pal.cyan, 0.05f, 0.0f, 380, 75),
        blob(Pal.violet, 0.95f, 0.45f, 440, 70),
        blob(Pal.pink, 0.15f, 0.95f, 320, 55)
    ))
}

fun Context.tv(s: String, sz: Float = 14f, color: Int = Pal.text, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = sz; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
}

fun Context.label(s: String) = tv(s.uppercase(), 11f, Pal.cyan, true).apply {
    letterSpacing = 0.14f; setPadding(0, 0, 0, dp(6))
}

fun Context.pill(t: String, fill: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(fg); textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
    setPadding(dp(12), dp(11), dp(12), dp(11)); background = shape(fill, 20); setOnClickListener { f() }
}

/** Tombol utama bergradien dengan feedback tap. */
fun Context.gpill(t: String, f: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
    setPadding(dp(14), dp(12), dp(14), dp(12)); background = grad(20); setOnClickListener { f() }
}

/** Tombol sekunder berkaca modern. */
fun Context.ghost(t: String, fg: Int = Pal.text, f: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(fg); textSize = 12f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
    setPadding(dp(12), dp(11), dp(12), dp(11)); background = shape(Pal.glass, 20, Pal.edge); setOnClickListener { f() }
}

fun Context.chip(s: String, c: Int) = TextView(this).apply {
    text = s; textSize = 11f; setTextColor(c); setTypeface(null, Typeface.BOLD)
    setPadding(dp(10), dp(4), dp(10), dp(4)); background = shape(tint(c, 40), 12, tint(c, 90))
}

/** Kartu kaca modern dengan border halus. */
fun Context.card() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(16), dp(18), dp(16))
    background = shape(Pal.glass, 22, Pal.edge)
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
}

fun Context.row(vararg v: View) = LinearLayout(this).apply {
    setPadding(0, dp(10), 0, 0)
    v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
}

fun Context.field(h: String) = EditText(this).apply {
    hint = h; setHintTextColor(Pal.mute); setTextColor(Pal.text); textSize = 14f
    background = shape(tint(Color.WHITE, 16), 14, Pal.edge); setPadding(dp(14), dp(12), dp(14), dp(12))
}

fun EditText.onText(f: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
    })
}

fun Context.divider(c: Int = Pal.edge) = View(this).apply {
    background = ColorDrawable(c)
    layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(10), 0, dp(10)) }
}

fun Context.iconPill(icon: String, text: String, color: Int = Pal.text, onClick: () -> Unit) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER
    background = shape(Pal.glass, 18, Pal.edge)
    setPadding(dp(12), dp(8), dp(12), dp(8))
    addView(tv(icon, 14f))
    addView(tv(text, 12f, color, true).apply { setPadding(dp(6), 0, 0, 0) })
    setOnClickListener { onClick() }
}

/** Dialog hasil AI: teks bisa dipilih, Salin, Bagikan. [extra] = tombol tambahan di bawah teks. */
fun Activity.showText(title: String, out: String, note: String = "", extra: View? = null) {
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
    if (note.isNotEmpty()) col.addView(tv(note, 12f, Pal.amber).apply { setPadding(0, 0, 0, dp(8)) })
    col.addView(tv(out, 14.5f, Pal.text).apply { setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1.15f) })
    if (extra != null) col.addView(extra, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(col) })
        .setPositiveButton("Salin") { _, _ ->
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("hasil", out))
            Toast.makeText(this, "Disalin ke papan klip", Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton("Bagikan") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out), null)) }
        .setNegativeButton("Tutup", null).show()
}
