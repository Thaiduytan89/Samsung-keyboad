package com.example.samkeyboard

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Bàn phím QWERTY kiểu Samsung: hàng số trên cùng, Shift (chạm đúp = Caps Lock),
 * giữ phím xóa để xóa liên tục, tự viết hoa đầu câu, chạm đúp dấu cách = ". ",
 * 2 trang ký hiệu, giao diện sáng/tối theo hệ thống.
 */
class SamKeyboardService : InputMethodService() {

    private enum class Layer { LETTERS, SYMBOLS_1, SYMBOLS_2 }
    private enum class Shift { OFF, ON, LOCK }
    private enum class Kind { TEXT, SHIFT, DELETE, TO_SYMBOLS_1, TO_SYMBOLS_2, TO_LETTERS, SPACE, ENTER, SPACER }

    private class Key(val kind: Kind, val label: String = "", val weight: Float = 1f)

    private class Colors(
        val background: Int,
        val key: Int,
        val special: Int,
        val pressed: Int,
        val text: Int,
        val accent: Int,
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout

    private var layer = Layer.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L

    // Dịch
    private class Lang(val code: String, val name: String)

    private val languages = listOf(
        Lang(TranslateLanguage.VIETNAMESE, "Tiếng Việt"),
        Lang(TranslateLanguage.ENGLISH, "English"),
        Lang(TranslateLanguage.CHINESE, "中文"),
        Lang(TranslateLanguage.JAPANESE, "日本語"),
        Lang(TranslateLanguage.KOREAN, "한국어"),
        Lang(TranslateLanguage.FRENCH, "Français"),
        Lang(TranslateLanguage.GERMAN, "Deutsch"),
        Lang(TranslateLanguage.SPANISH, "Español"),
        Lang(TranslateLanguage.THAI, "ไทย"),
        Lang(TranslateLanguage.RUSSIAN, "Русский"),
    )
    private var translateOpen = false
    private var translateStatus: String? = null
    private var srcIndex = 0 // Tiếng Việt
    private var dstIndex = 1 // English
    private var translator: Translator? = null
    private var translatorKey = ""

    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteOnce()
            handler.postDelayed(this, 45)
        }
    }

    // ---------------------------------------------------------------- vòng đời

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        rebuild()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        translateStatus = null
        layer = Layer.LETTERS
        shift = if (capsMode() != 0) Shift.ON else Shift.OFF
        rebuild()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(repeatDelete)
        super.onFinishInputView(finishingInput)
    }

    // ---------------------------------------------------------------- bố cục

    private fun t(s: String) = Key(Kind.TEXT, s)

    private fun layout(): List<List<Key>> {
        val numbers = "1234567890".map { t(it.toString()) }
        val bottomLetters = listOf(
            Key(Kind.TO_SYMBOLS_1, "?123", 1.5f), t(","),
            Key(Kind.SPACE, "English", 5f), t("."),
            Key(Kind.ENTER, "↵", 1.5f),
        )
        val bottomSymbols = listOf(
            Key(Kind.TO_LETTERS, "ABC", 1.5f), t(","),
            Key(Kind.SPACE, "English", 5f), t("."),
            Key(Kind.ENTER, "↵", 1.5f),
        )
        return when (layer) {
            Layer.LETTERS -> listOf(
                numbers,
                "qwertyuiop".map { t(it.toString()) },
                listOf(Key(Kind.SPACER, weight = 0.5f)) +
                    "asdfghjkl".map { t(it.toString()) } +
                    listOf(Key(Kind.SPACER, weight = 0.5f)),
                listOf(Key(Kind.SHIFT, "⇧", 1.5f)) +
                    "zxcvbnm".map { t(it.toString()) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.5f)),
                bottomLetters,
            )
            Layer.SYMBOLS_1 -> listOf(
                numbers,
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { t(it) },
                listOf(Key(Kind.SPACER, weight = 1.5f)) +
                    listOf("*", "\"", "'", ":", ";", "!", "?").map { t(it) } +
                    listOf(Key(Kind.SPACER, weight = 1.5f)),
                listOf(Key(Kind.TO_SYMBOLS_2, "=\\<", 1.5f)) +
                    listOf("%", "^", "[", "]", "{", "}").map { t(it) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.5f)),
                bottomSymbols,
            )
            Layer.SYMBOLS_2 -> listOf(
                numbers,
                listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { t(it) },
                listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { t(it) },
                listOf(Key(Kind.TO_SYMBOLS_1, "?123", 1.5f)) +
                    listOf("%", "©", "®", "™", "[", "]").map { t(it) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.5f)),
                bottomSymbols,
            )
        }
    }

    private fun colors(): Colors {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            Colors(
                background = Color.parseColor("#202124"),
                key = Color.parseColor("#3C3F44"),
                special = Color.parseColor("#2A2C30"),
                pressed = Color.parseColor("#5A5E65"),
                text = Color.parseColor("#F1F1F1"),
                accent = Color.parseColor("#3E91FF"),
            )
        } else {
            Colors(
                background = Color.parseColor("#E9EAEE"),
                key = Color.WHITE,
                special = Color.parseColor("#D4D6DD"),
                pressed = Color.parseColor("#BFC3CC"),
                text = Color.parseColor("#1B1B1B"),
                accent = Color.parseColor("#3E91FF"),
            )
        }
    }

    // ---------------------------------------------------------------- dựng giao diện

    private fun rebuild() {
        if (!::root.isInitialized) return
        val c = colors()
        root.removeAllViews()
        root.setBackgroundColor(c.background)
        root.addView(buildToolbar(c))

        for (row in layout()) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52),
                )
            }
            for (key in row) rowView.addView(makeKeyView(key, c))
            root.addView(rowView)
        }
    }

    private fun displayLabel(key: Key): String =
        if (key.kind == Kind.TEXT && layer == Layer.LETTERS && shift != Shift.OFF) {
            key.label.uppercase()
        } else key.label

    private fun makeKeyView(key: Key, c: Colors): View {
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, key.weight)
            .apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }

        if (key.kind == Kind.SPACER) {
            return View(this).apply { layoutParams = params }
        }

        val isAccent = key.kind == Kind.ENTER ||
            (key.kind == Kind.SHIFT && shift != Shift.OFF)
        val isSpecial = key.kind != Kind.TEXT && key.kind != Kind.SPACE
        val baseColor = when {
            isAccent -> c.accent
            isSpecial -> c.special
            else -> c.key
        }
        val label = if (key.kind == Kind.SHIFT && shift == Shift.LOCK) "⇪" else displayLabel(key)

        return TextView(this).apply {
            layoutParams = params
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (key.kind == Kind.ENTER || (key.kind == Kind.SHIFT && shift != Shift.OFF)) Color.WHITE else c.text)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                when (key.kind) {
                    Kind.TEXT -> 20f
                    Kind.SPACE -> 13f
                    else -> 16f
                },
            )
            background = rounded(baseColor)
            isClickable = true
            setOnTouchListener { v, ev -> handleTouch(v, ev, key, baseColor, c) }
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun handleTouch(v: View, ev: MotionEvent, key: Key, baseColor: Int, c: Colors): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.background = rounded(c.pressed)
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                if (key.kind == Kind.DELETE) {
                    deleteOnce()
                    handler.postDelayed(repeatDelete, 400)
                }
            }
            MotionEvent.ACTION_UP -> {
                v.background = rounded(baseColor)
                if (key.kind == Kind.DELETE) {
                    handler.removeCallbacks(repeatDelete)
                    refreshShift()
                } else {
                    val inside = ev.x >= 0 && ev.x <= v.width && ev.y >= 0 && ev.y <= v.height
                    if (inside) onKey(key)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                v.background = rounded(baseColor)
                handler.removeCallbacks(repeatDelete)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- xử lý phím

    private fun onKey(key: Key) {
        when (key.kind) {
            Kind.TEXT -> {
                currentInputConnection?.commitText(displayLabel(key), 1)
                val before = shift
                if (shift == Shift.ON) shift = Shift.OFF
                refreshShift(forceRebuild = before != shift)
            }
            Kind.SPACE -> onSpace()
            Kind.ENTER -> onEnter()
            Kind.SHIFT -> onShiftTap()
            Kind.TO_SYMBOLS_1 -> { layer = Layer.SYMBOLS_1; rebuild() }
            Kind.TO_SYMBOLS_2 -> { layer = Layer.SYMBOLS_2; rebuild() }
            Kind.TO_LETTERS -> {
                layer = Layer.LETTERS
                if (shift == Shift.OFF && capsMode() != 0) shift = Shift.ON
                rebuild()
            }
            else -> Unit
        }
    }

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.LOCK -> Shift.OFF
            now - lastShiftTap < 350 -> Shift.LOCK
            shift == Shift.OFF -> Shift.ON
            else -> Shift.OFF
        }
        lastShiftTap = now
        rebuild()
    }

    private fun deleteOnce() {
        sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
    }

    private fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)
        if (now - lastSpaceTap < 400 && before != null && before.length == 2 &&
            before[1] == ' ' && before[0].isLetterOrDigit()
        ) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTap = 0
        } else {
            ic.commitText(" ", 1)
            lastSpaceTap = now
        }
        refreshShift()
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (info != null && !noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        refreshShift()
    }

    // ---------------------------------------------------------------- tự viết hoa

    private fun capsMode(): Int {
        val ic = currentInputConnection ?: return 0
        val inputType = currentInputEditorInfo?.inputType ?: 0
        return ic.getCursorCapsMode(inputType)
    }

    /** Cập nhật Shift theo ngữ cảnh (đầu câu thì tự bật Shift). */
    private fun refreshShift(forceRebuild: Boolean = false) {
        val before = shift
        if (layer == Layer.LETTERS && shift == Shift.OFF && capsMode() != 0) {
            shift = Shift.ON
        }
        if (shift != before || forceRebuild) rebuild()
    }

    // ---------------------------------------------------------------- dịch

    private fun buildToolbar(c: Colors): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44),
            )
        }

        fun chip(label: String, weight: Float, accent: Boolean = false, onClick: () -> Unit): View =
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, weight,
                ).apply { setMargins(dp(2), dp(3), dp(2), dp(5)) }
                this.text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(if (accent) Color.WHITE else c.text)
                background = rounded(if (accent) c.accent else c.special)
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            }

        if (!translateOpen) {
            bar.addView(chip("Dịch", 1.6f) {
                translateOpen = true
                translateStatus = null
                rebuild()
            })
            bar.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 6f,
                )
            })
        } else {
            bar.addView(chip(languages[srcIndex].name, 1.5f) {
                srcIndex = nextLanguage(srcIndex, dstIndex)
                rebuild()
            })
            bar.addView(chip("⇄", 0.8f) {
                val tmp = srcIndex
                srcIndex = dstIndex
                dstIndex = tmp
                rebuild()
            })
            bar.addView(chip(languages[dstIndex].name, 1.5f) {
                dstIndex = nextLanguage(dstIndex, srcIndex)
                rebuild()
            })
            bar.addView(chip(translateStatus ?: "Dịch", 1.6f, accent = true) {
                translateNow()
            })
            bar.addView(chip("✕", 0.8f) {
                translateOpen = false
                translateStatus = null
                rebuild()
            })
        }
        return bar
    }

    private fun nextLanguage(current: Int, other: Int): Int {
        var i = current
        do {
            i = (i + 1) % languages.size
        } while (i == other)
        return i
    }

    private fun setStatus(status: String?, clearAfterMs: Long = 0) {
        translateStatus = status
        rebuild()
        if (clearAfterMs > 0) {
            handler.postDelayed({
                translateStatus = null
                rebuild()
            }, clearAfterMs)
        }
    }

    private fun translatorFor(src: String, dst: String): Translator {
        val key = "$src>$dst"
        if (key != translatorKey || translator == null) {
            translator?.close()
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(dst)
                    .build(),
            )
            translatorKey = key
        }
        return translator!!
    }

    /** Dịch đoạn đang chọn, nếu không chọn gì thì dịch toàn bộ chữ trong ô nhập rồi thay thế. */
    private fun translateNow() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val useSelection = selected.isNotEmpty()
        val beforeText = ic.getTextBeforeCursor(5000, 0)?.toString().orEmpty()
        val afterText = ic.getTextAfterCursor(5000, 0)?.toString().orEmpty()
        val source = if (useSelection) selected else beforeText + afterText

        if (source.isBlank()) {
            setStatus("Chưa có chữ", 2000)
            return
        }

        setStatus("Đang dịch...")
        val tr = translatorFor(languages[srcIndex].code, languages[dstIndex].code)
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                tr.translate(source)
                    .addOnSuccessListener { result ->
                        val target = currentInputConnection
                        if (target != null) {
                            target.beginBatchEdit()
                            if (!useSelection) {
                                target.deleteSurroundingText(beforeText.length, afterText.length)
                            }
                            target.commitText(result, 1)
                            target.endBatchEdit()
                        }
                        setStatus(null)
                    }
                    .addOnFailureListener { setStatus("Lỗi dịch", 2500) }
            }
            .addOnFailureListener { setStatus("Cần mạng", 2500) }
    }

    override fun onDestroy() {
        translator?.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
        ).toInt()
}
