package id.pemantau.tugas

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Pal {
    val bg = 0xFFF3F5FB.toInt()
    val ink = 0xFF1B2133.toInt()
    val mute = 0xFF5D6679.toInt()
    val blue = 0xFF2D5BFF.toInt()
    val violet = 0xFF7C4DFF.toInt()
    val red = 0xFFC62828.toInt()
    val amber = 0xFFB45309.toInt()
    val green = 0xFF15803D.toInt()
    val soft = 0xFFE9EDF8.toInt()
}

fun tint(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

fun Context.shape(fill: Int, r: Int = 16, stroke: Int = 0) = GradientDrawable().apply {
    setColor(fill); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.tv(s: String, sz: Float = 14f, color: Int = Pal.ink, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = sz; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
}

fun Context.pill(t: String, fill: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(fg); textSize = 13f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
    setPadding(dp(8), dp(11), dp(8), dp(11)); background = shape(fill, 20); setOnClickListener { f() }
}

fun Context.chip(s: String, c: Int) = TextView(this).apply {
    text = s; textSize = 11f; setTextColor(c); setTypeface(null, Typeface.BOLD)
    setPadding(dp(10), dp(4), dp(10), dp(4)); background = shape(tint(c, 32), 12)
}

fun Context.card() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = shape(Color.WHITE, 20); elevation = dp(2).toFloat()
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
}

fun Context.row(vararg v: View) = LinearLayout(this).apply {
    setPadding(0, dp(10), 0, 0)
    v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
}
