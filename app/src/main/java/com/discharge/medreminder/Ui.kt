package com.discharge.medreminder

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.isDarkTheme(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.fontScale(): Float = if (Prefs.largeText(this)) 1.2f else 1.0f

fun Context.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.horizontal() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size * fontScale()
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(4), 0, dp(4))
}

fun Context.badge(s: String, bgColor: Int, fgColor: Int = Color.WHITE) = TextView(this).apply {
    text = s
    textSize = 12f * fontScale()
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(fgColor)
    setPadding(dp(8), dp(3), dp(8), dp(3))
    background = GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = dp(12).toFloat()
    }
}

fun Context.button(s: String, onClick: () -> Unit) = Button(this).apply {
    text = s
    textSize = 14f * fontScale()
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Context.checkBox(s: String, checked: Boolean, onChanged: (Boolean) -> Unit = {}) = CheckBox(this).apply {
    text = s
    textSize = 14f * fontScale()
    isChecked = checked
    setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
}

fun Context.field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setText(value)
    textSize = 15f * fontScale()
    if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
}

fun Context.card(): LinearLayout = vertical().apply {
    val dark = isDarkTheme()
    setPadding(dp(12), dp(12), dp(12), dp(12))
    background = GradientDrawable().apply {
        setColor(if (dark) 0xFF1E293B.toInt() else 0xFFF4F8FB.toInt())
        setStroke(dp(1), if (dark) 0xFF334155.toInt() else 0xFFB0C4D4.toInt())
        cornerRadius = dp(8).toFloat()
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(6), 0, dp(6)) }
}

fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

/** Opens a 24-hour TimePickerDialog pre-filled with [initial] ("HH:mm") and returns the chosen "HH:mm". */
fun Context.pickTime(initial: String = "08:00", onPicked: (String) -> Unit) {
    val parts = (normalizeTime(initial) ?: "08:00").split(":")
    val initH = parts.getOrNull(0)?.toIntOrNull() ?: 8
    val initM = parts.getOrNull(1)?.toIntOrNull() ?: 0
    TimePickerDialog(this, { _, h, m ->
        onPicked("%02d:%02d".format(h, m))
    }, initH, initM, true).show()
}

/** Shows a full-size preview dialog of a bitmap (such as the scanned discharge sheet). */
fun Context.showImageDialog(title: String, bmp: Bitmap) {
    val iv = ImageView(this).apply {
        setImageBitmap(bmp)
        adjustViewBounds = true
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(iv)
        .setPositiveButton("Close", null)
        .show()
}
