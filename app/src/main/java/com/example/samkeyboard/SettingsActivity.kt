package com.example.samkeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Màn hình hướng dẫn bật bàn phím + ô gõ thử. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(TextView(this).apply {
            text = "SamKeyboard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, 0, 0, dp(8))
        })

        column.addView(TextView(this).apply {
            text = "Làm theo 2 bước để dùng bàn phím:\n\n" +
                "1. Bấm nút bên dưới, bật công tắc SamKeyboard.\n" +
                "2. Bấm nút thứ hai, chọn SamKeyboard làm bàn phím đang dùng."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(16))
        })

        column.addView(Button(this).apply {
            text = "1. Bật bàn phím trong Cài đặt"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        column.addView(Button(this).apply {
            text = "2. Chọn SamKeyboard để gõ"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        })

        column.addView(EditText(this).apply {
            hint = "Gõ thử ở đây"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) }
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
        ).toInt()
}
