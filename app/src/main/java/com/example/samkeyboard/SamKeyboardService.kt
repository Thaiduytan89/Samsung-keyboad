package com.example.samkeyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * Bàn phím QWERTY kiểu Samsung:
 *  - Chuyển đổi English / Tiếng Việt (gõ Telex) bằng phím EN/VI
 *  - Dịch sang English, 繁體中文, မြန်မာ (có ô nhập riêng trong bảng dịch, kiểu Gboard/Samsung)
 *  - Shift (chạm đúp = Caps Lock), giữ phím xóa để xóa liên tục, tự viết hoa đầu câu,
 *    chạm đúp dấu cách = ". ", 2 trang ký hiệu, giao diện sáng/tối theo hệ thống.
 */
class SamKeyboardService : InputMethodService() {

    private enum class Layer { LETTERS, SYMBOLS_1, SYMBOLS_2 }
    private enum class Shift { OFF, ON, LOCK }
    private enum class Kind {
        TEXT, SHIFT, DELETE, TO_SYMBOLS_1, TO_SYMBOLS_2, TO_LETTERS, LANG, SPACE, ENTER, SPACER,
    }

    private class Key(val kind: Kind, val label: String = "", val weight: Float = 1f)

    private class Colors(
        val background: Int,
        val key: Int,
        val special: Int,
        val pressed: Int,
        val text: Int,
        val accent: Int,
    )

    private class Target(val code: String, val name: String)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout

    private var layer = Layer.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L

    // Gõ tiếng Việt
    private val telex = TelexEngine()
    private var vietnamese = true
    private var telexAllowed = true

    // Dịch
    private val targets = listOf(
        Target("en", "English"),
        Target("zh-TW", "繁體中文"), // 繁體中文
        Target("my", "မြန်မာ"), // မြန်မာ
    )
    private var translateOpen = false
    private var translateStatus: String? = null
    private var targetIndex = 0
    private var translateJob = 0

    // Bảng dịch có ô nhập riêng (giống Gboard/Samsung): gõ và dịch ngay trong bàn phím
    private var trCommitted = ""
    private val trTelex = TelexEngine()
    private var trResult = ""
    private var trTranslating = false
    private var trSnapUseSelection = false
    private var trSnapSelected = ""
    private var trSnapBefore = ""
    private var trSnapAfter = ""

    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteOnce()
            handler.postDelayed(this, 45)
        }
    }

    private val trAutoTranslate = Runnable { translateNow(auto = true) }

    // ---------------------------------------------------------------- vòng đời

    override fun onCreate() {
        super.onCreate()
        vietnamese = prefs().getBoolean("vietnamese", true)
    }

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
        finishTelex()
        telexAllowed = allowsTelex(info)
        translateStatus = null
        resetTranslateState()
        layer = Layer.LETTERS
        shift = if (capsMode() != 0) Shift.ON else Shift.OFF
        rebuild()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(repeatDelete)
        finishTelex()
        resetTranslateState()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        // Người dùng đưa con trỏ đi chỗ khác: kết thúc từ đang gõ dở.
        if (!telex.isEmpty &&
            (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            finishTelex()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("samkeyboard", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- bố cục

    private fun t(s: String) = Key(Kind.TEXT, s)

    private fun layout(): List<List<Key>> {
        val numbers = "1234567890".map { t(it.toString()) }
        val bottomLetters = listOf(
            Key(Kind.TO_SYMBOLS_1, "?123", 1.3f),
            Key(Kind.LANG, "", 1.2f),
            t(","),
            Key(Kind.SPACE, "", 4f),
            t("."),
            Key(Kind.ENTER, "↵", 1.5f),
        )
        val bottomSymbols = listOf(
            Key(Kind.TO_LETTERS, "ABC", 1.5f),
            t(","),
            Key(Kind.SPACE, "", 5f),
            t("."),
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

    private fun keyLabel(key: Key): String = when {
        key.kind == Kind.SHIFT && shift == Shift.LOCK -> "⇪"
        key.kind == Kind.LANG -> if (vietnamese) "VI" else "EN"
        key.kind == Kind.SPACE -> if (vietnamese) "Tiếng Việt" else "English"
        else -> displayLabel(key)
    }

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

        return TextView(this).apply {
            layoutParams = params
            text = keyLabel(key)
            gravity = Gravity.CENTER
            setTextColor(if (isAccent) Color.WHITE else c.text)
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

    // ---------------------------------------------------------------- gõ tiếng Việt (Telex)

    private fun allowsTelex(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if ((type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
            variation != InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun telexOn(): Boolean = vietnamese && telexAllowed && layer == Layer.LETTERS
    private fun trTelexOn(): Boolean = vietnamese && layer == Layer.LETTERS

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** Hiển thị từ đang gõ dưới dạng "composing text" (có gạch chân) để còn sửa dấu được. */
    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        if (telex.isEmpty) {
            ic.commitText("", 1)
        } else {
            ic.setComposingText(telex.text(), 1)
        }
    }

    /** Chốt từ đang gõ (khi gặp dấu cách, dấu câu, đổi trang phím, ...). */
    private fun finishTelex() {
        if (telex.isEmpty) return
        telex.reset()
        currentInputConnection?.finishComposingText()
    }

    // ---------------------------------------------------------------- xử lý phím

    private fun onKey(key: Key) {
        if (translateOpen) {
            onTranslateKey(key)
            return
        }
        when (key.kind) {
            Kind.TEXT, Kind.SHIFT, Kind.DELETE, Kind.SPACER -> Unit
            else -> finishTelex()
        }
        when (key.kind) {
            Kind.TEXT -> {
                val text = displayLabel(key)
                if (telexOn() && text.length == 1 && isAsciiLetter(text[0])) {
                    telex.feed(text[0])
                    updateComposing()
                } else {
                    finishTelex()
                    currentInputConnection?.commitText(text, 1)
                }
                val before = shift
                if (shift == Shift.ON) shift = Shift.OFF
                refreshShift(forceRebuild = before != shift)
            }
            Kind.SPACE -> onSpace()
            Kind.ENTER -> onEnter()
            Kind.SHIFT -> onShiftTap()
            Kind.LANG -> {
                vietnamese = !vietnamese
                prefs().edit().putBoolean("vietnamese", vietnamese).apply()
                rebuild()
            }
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
        if (!telex.isEmpty) {
            telex.backspace()
            updateComposing()
            return
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
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

    // ---------------------------------------------------------------- bảng dịch (giống Gboard/Samsung)

    private fun trSourceText(): String = trCommitted + trTelex.text()

    private fun finishTrTelex() {
        if (!trTelex.isEmpty) {
            trCommitted += trTelex.text()
            trTelex.reset()
        }
    }

    private fun resetTranslateState() {
        handler.removeCallbacks(trAutoTranslate)
        translateOpen = false
        trTranslating = false
        translateStatus = null
        trResult = ""
        trCommitted = ""
        trTelex.reset()
        trSnapUseSelection = false
        trSnapSelected = ""
        trSnapBefore = ""
        trSnapAfter = ""
        translateJob++
    }

    private fun openTranslate() {
        finishTelex()
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(0)?.toString().orEmpty()
        val before = ic?.getTextBeforeCursor(5000, 0)?.toString().orEmpty()
        val after = ic?.getTextAfterCursor(5000, 0)?.toString().orEmpty()
        trSnapUseSelection = selected.isNotEmpty()
        trSnapSelected = selected
        trSnapBefore = before
        trSnapAfter = after
        val source = if (trSnapUseSelection) selected else before + after
        trCommitted = source
        trTelex.reset()
        trResult = ""
        translateStatus = null
        trTranslating = false
        translateOpen = true
        layer = Layer.LETTERS
        rebuild()
        if (source.isNotBlank()) {
            translateNow(auto = true)
        }
    }

    private fun closeTranslate() {
        finishTrTelex()
        handler.removeCallbacks(trAutoTranslate)
        translateJob++
        translateOpen = false
        trTranslating = false
        translateStatus = null
        trResult = ""
        trCommitted = ""
        trTelex.reset()
        rebuild()
    }

    private fun onTranslateKey(key: Key) {
        when (key.kind) {
            Kind.TEXT -> {
                val text = displayLabel(key)
                if (trTelexOn() && text.length == 1 && isAsciiLetter(text[0])) {
                    trTelex.feed(text[0])
                } else {
                    finishTrTelex()
                    trCommitted += text
                }
                if (shift == Shift.ON) shift = Shift.OFF
            }
            Kind.SPACE -> {
                finishTrTelex()
                val now = SystemClock.uptimeMillis()
                if (now - lastSpaceTap < 400 && trCommitted.endsWith(" ") && trCommitted.length >= 2 &&
                    trCommitted[trCommitted.length - 2].isLetterOrDigit()
                ) {
                    trCommitted = trCommitted.dropLast(1) + ". "
                    lastSpaceTap = 0
                } else {
                    trCommitted += " "
                    lastSpaceTap = now
                }
            }
            Kind.ENTER -> {
                finishTrTelex()
                translateNow()
                return
            }
            Kind.DELETE -> {
                if (!trTelex.isEmpty) {
                    trTelex.backspace()
                } else if (trCommitted.isNotEmpty()) {
                    trCommitted = trCommitted.dropLast(1)
                }
            }
            Kind.SHIFT -> {
                onShiftTap()
                return
            }
            Kind.LANG -> {
                finishTrTelex()
                vietnamese = !vietnamese
                prefs().edit().putBoolean("vietnamese", vietnamese).apply()
            }
            Kind.TO_SYMBOLS_1 -> {
                finishTrTelex()
                layer = Layer.SYMBOLS_1
            }
            Kind.TO_SYMBOLS_2 -> {
                finishTrTelex()
                layer = Layer.SYMBOLS_2
            }
            Kind.TO_LETTERS -> {
                finishTrTelex()
                layer = Layer.LETTERS
                if (shift == Shift.OFF && capsMode() != 0) shift = Shift.ON
            }
            Kind.SPACER -> return
        }
        trResult = ""
        if (translateStatus != null && !trTranslating) translateStatus = null
        refreshShift()
        scheduleTrAutoTranslate()
        rebuild()
    }

    private fun buildToolbar(c: Colors): View {
        if (!translateOpen) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44),
                )
            }
            bar.addView(chip("Dịch", 1.6f, c) { openTranslate() })
            bar.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 6f,
                )
            })
            return bar
        }
        return buildTranslatePanel(c)
    }

    private fun buildTranslatePanel(c: Colors): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val control = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40),
            )
        }
        control.addView(chip("Sang: " + targets[targetIndex].name, 2.6f, c) {
            targetIndex = (targetIndex + 1) % targets.size
            rebuild()
            if (trSourceText().isNotBlank()) translateNow()
        })
        val actionLabel = when {
            trTranslating -> "Đang dịch..."
            translateStatus != null && trResult.isEmpty() -> translateStatus!!
            else -> "Dịch"
        }
        control.addView(chip(actionLabel, 1.4f, c, accent = true) { translateNow() })
        control.addView(chip("✕", 0.7f, c) { closeTranslate() })
        panel.addView(control)

        val srcText = trSourceText()
        val srcView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44),
            ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            text = if (srcText.isEmpty()) "Nhập văn bản cần dịch..." else srcText
            setTextColor(if (srcText.isEmpty()) Color.GRAY else c.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(c.key)
            maxLines = 2
        }
        panel.addView(srcView)

        val resultRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42),
            )
        }
        val resLabel = when {
            trTranslating -> "Đang dịch..."
            trResult.isNotEmpty() -> trResult
            translateStatus != null -> translateStatus!!
            else -> "Bản dịch sẽ hiện ở đây"
        }
        resultRow.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 3.2f,
            ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            text = resLabel
            setTextColor(
                if (trResult.isNotEmpty() && !trTranslating) c.text else Color.GRAY
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(c.special)
            maxLines = 2
        })
        resultRow.addView(
            chip("Thay thế", 1.3f, c, accent = trResult.isNotEmpty()) { applyTranslation() }
        )
        panel.addView(resultRow)

        return panel
    }

    private fun chip(
        label: String, weight: Float, c: Colors, accent: Boolean = false, onClick: () -> Unit,
    ): View = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            .apply { setMargins(dp(2), dp(3), dp(2), dp(5)) }
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (accent) Color.WHITE else c.text)
        background = rounded(if (accent) c.accent else c.special)
        isClickable = true
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    private fun setTrStatus(status: String?, clearAfterMs: Long = 0) {
        translateStatus = status
        trTranslating = false
        rebuild()
        if (clearAfterMs > 0) {
            val snapshot = status
            handler.postDelayed({
                if (translateStatus == snapshot) {
                    translateStatus = null
                    rebuild()
                }
            }, clearAfterMs)
        }
    }

    /**
     * Dịch nội dung trong ô nhập của bảng dịch (tự nhận diện ngôn ngữ nguồn).
     * Khi mở bảng dịch sẽ tự lấy và dịch văn bản đang soạn/chọn trong ô nhập.
     */
    private fun translateNow(auto: Boolean = false) {
        handler.removeCallbacks(trAutoTranslate)
        finishTrTelex()
        val source = trSourceText()
        if (source.isBlank()) {
            if (!auto) setTrStatus("Chưa có chữ", 2000)
            return
        }
        if (source.length > 4500) {
            setTrStatus("Quá dài", 2500)
            return
        }

        val job = ++translateJob
        val target = targets[targetIndex].code
        trTranslating = true
        translateStatus = "Đang dịch..."
        trResult = ""
        rebuild()

        Thread {
            var out: String? = null
            var err = "Lỗi dịch"
            try {
                out = requestTranslation(source, target)
            } catch (e: UnknownHostException) {
                err = "Không có mạng"
            } catch (e: SocketTimeoutException) {
                err = "Hết thời gian, thử lại"
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                err = when {
                    msg.contains("HTTP 429") -> "Bị giới hạn, thử lại sau"
                    msg.startsWith("HTTP") -> "Lỗi máy chủ, thử lại"
                    else -> "Lỗi mạng, thử lại"
                }
            } catch (e: Exception) {
                err = "Lỗi dịch"
            }
            handler.post { onTrTranslated(job, out, err) }
        }.start()
    }

    private fun onTrTranslated(job: Int, out: String?, err: String) {
        if (job != translateJob) return
        trTranslating = false
        if (out.isNullOrEmpty()) {
            translateStatus = err
            trResult = ""
        } else {
            translateStatus = null
            trResult = out
        }
        rebuild()
    }

    /** Thay thế văn bản trong ô nhập của ứng dụng bằng bản dịch. */
    private fun applyTranslation() {
        if (trResult.isEmpty()) {
            setTrStatus("Chưa có bản dịch", 1500)
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            setTrStatus("Không thể chèn", 1500)
            return
        }
        ic.beginBatchEdit()
        try {
            if (trSnapUseSelection) {
                val sel = ic.getSelectedText(0)?.toString().orEmpty()
                // Nếu vùng chọn vẫn như lúc mở bảng dịch thì thay thế, ngược lại chèn tại con trỏ
                if (sel == trSnapSelected) {
                    ic.commitText(trResult, 1)
                } else {
                    ic.commitText(trResult, 1)
                }
            } else {
                val nowBefore = ic.getTextBeforeCursor(5000, 0)?.toString().orEmpty()
                val nowAfter = ic.getTextAfterCursor(5000, 0)?.toString().orEmpty()
                if (nowBefore == trSnapBefore && nowAfter == trSnapAfter) {
                    ic.deleteSurroundingText(nowBefore.length, nowAfter.length)
                    ic.commitText(trResult, 1)
                } else {
                    ic.commitText(trResult, 1)
                }
            }
        } finally {
            ic.endBatchEdit()
        }
        closeTranslate()
    }

    /**
     * Gọi dịch vụ dịch của Google (không cần khóa API), tự nhận diện ngôn ngữ nguồn.
     * Chữ cần dịch được gửi lên Google khi bấm nút Dịch.
     */
    private fun requestTranslation(text: String, target: String): String {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val params = "client=gtx&sl=auto&tl=" + URLEncoder.encode(target, "UTF-8") + "&dt=t"
        val base = "https://translate.googleapis.com/translate_a/single"
        val usePost = encodedText.length > 1500

        val url = if (usePost) URL(base) else URL("$base?$params&q=$encodedText")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept", "*/*")
            if (usePost) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8",
                )
                conn.outputStream.use {
                    it.write("$params&q=$encodedText".toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val sentences = JSONArray(json).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val part = sentences.optJSONArray(i) ?: continue
                if (part.isNull(0)) continue
                sb.append(part.optString(0, ""))
            }
            val result = sb.toString()
            if (result.isEmpty()) throw IOException("empty")
            return result
        } finally {
            conn.disconnect()
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
        ).toInt()
}
