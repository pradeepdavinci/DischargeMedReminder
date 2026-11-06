package com.discharge.medreminder

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.horizontal() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
}

fun Context.label(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(4), 0, dp(4))
}

fun Context.button(s: String, onClick: () -> Unit) = Button(this).apply {
    text = s; isAllCaps = false; setOnClickListener { onClick() }
}

fun Context.field(hint: String, value: String, numeric: Boolean = false) = EditText(this).apply {
    this.hint = hint; setText(value)
    if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
}

fun Context.card(): LinearLayout = vertical().apply {
    setPadding(dp(12), dp(12), dp(12), dp(12))
    background = GradientDrawable().apply {
        setColor(0xFFF4F8FB.toInt()); setStroke(dp(1), 0xFFB0C4D4.toInt()); cornerRadius = dp(8).toFloat()
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(6), 0, dp(6)) }
}

fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
