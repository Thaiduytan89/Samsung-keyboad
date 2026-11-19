package com.example.samkeyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bàn phím QWERTY tối ưu cho màn hình gập (Oppo Find N5 trong/ngoài):
 *  - Màn hình trong (rộng): bàn phím tách đôi kiểu Gboard + dãy số, dấu cách liền, gõ 2 tay.
 *  - Màn hình ngoài (gập lại): giao diện bàn phím bình thường.
 *  - Tinh chỉnh (bố cục, chiều cao, lề mép, khoảng cách phím, theme, màu, âm thanh, sửa lỗi)
 *    được lưu RIÊNG BIỆT cho từng màn hình (trong/ngoài).
 *  - Gõ tiếng Việt Telex (QWERTY) và English (phím VI/EN).
 *  - Tự sửa chính tả khi bấm dấu cách (tuỳ chọn).
 *  - Nhập liệu giọng nói tiếng Việt; nhập liệu giọng nói tiếng Việt và dịch sang tiếng Anh.
 *  - Dịch sang English, 繁體中文, မြန်မာ: bấm Dịch để dịch và thay thế trực tiếp vào ô chat.
 *  - Giao diện iPhone (mặc định) / Samsung, đổi màu bàn phím, popup mượt.
 */
class SamKeyboardService : InputMethodService(), SpellCheckerSession.SpellCheckerSessionListener {

    private enum class Layer { LETTERS, SYMBOLS_1, SYMBOLS_2 }
    private enum class Shift { OFF, ON, LOCK }
    private enum class Kind {
        TEXT, SHIFT, DELETE, TO_SYMBOLS_1, TO_SYMBOLS_2, TO_LETTERS, LANG, SPACE, ENTER, SPACER,
    }
    private enum class KbLayout { NORMAL, SPLIT, FLOATING }

    private class Key(val kind: Kind, val label: String = "", val weight: Float = 1f)

    private class Colors(
        val background: Int,
        val key: Int,
        val special: Int,
        val pressedKey: Int,
        val pressedSpecial: Int,
        val text: Int,
        val specialText: Int,
        val accent: Int,
        val popupBg: Int,
        val popupText: Int,
        val panel: Int,
        val keyStroke: Int,
    )

    private class Target(val code: String, val name: String)

    private data class KbColor(val name: String, val accent: Int, val lightTint: Int, val darkTint: Int)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var container: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var popup: TextView

    private var layer = Layer.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L

    // ----- Tùy chỉnh (lưu riêng biệt cho từng màn hình: trong=rộng, ngoài=hẹp) -----
    private var wideLayoutPref = "split" // "normal", "split", "floating" (màn hình trong)
    private var keyHeightPref = 52
    private var floatMarginStartDp = 8
    private var floatMarginBottomDp = 8
    private var floatWidthDp = 340
    private var showKbSettings = false
    private var showDeepTune = false
    private var kbStyle = "iphone" // "iphone", "samsung"
    private var kbColorIndex = 0
    private var sideInsetDp = 0      // lề từ mép màn hình vào giữa
    private var keyHGapDp = 3        // khoảng cách ngang giữa các phím
    private var keyVGapDp = 4        // khoảng cách dọc giữa các phím
    private var soundMode = 1
    private var autoCorrect = true

    private val soundModes = listOf("Tắt", "Mặc định", "Nhẹ", "Click", "Rõ")

    private val kbColors = listOf(
        KbColor("Mặc định", Color.parseColor("#007AFF"), 0, 0),
        KbColor("Xanh dương", Color.parseColor("#007AFF"), Color.parseColor("#D6E4FF"), Color.parseColor("#0A2540")),
        KbColor("Xanh lá", Color.parseColor("#34C759"), Color.parseColor("#DFF5E3"), Color.parseColor("#0F2E17")),
        KbColor("Xanh ngọc", Color.parseColor("#00B3A4"), Color.parseColor("#D7F5F2"), Color.parseColor("#0A2E2B")),
        KbColor("Tím", Color.parseColor("#AF52DE"), Color.parseColor("#F0E0FA"), Color.parseColor("#2A1533")),
        KbColor("Hồng", Color.parseColor("#FF2D55"), Color.parseColor("#FFE0E8"), Color.parseColor("#33121A")),
        KbColor("Cam", Color.parseColor("#FF9500"), Color.parseColor("#FFE8CC"), Color.parseColor("#33210A")),
        KbColor("Xám", Color.parseColor("#8E8E93"), Color.parseColor("#E5E5EA"), Color.parseColor("#2C2C2E")),
    )

    // Gõ tiếng Việt (Telex trên bố cục QWERTY)
    private val telex = TelexEngine()
    private var vietnamese = true
    private var telexAllowed = true

    // Dịch
    private val targets = listOf(
        Target("en", "English"),
        Target("zh-TW", "繁體中文"),
        Target("my", "မြန်မာ"),
    )
    private var translateOpen = false
    private var translateStatus: String? = null
    private var targetIndex = 0
    private var translateJob = 0

    // Bảng dịch
    private var trCommitted = ""
    private val trTelex = TelexEngine()
    private var trResult = ""
    private var trTranslating = false
    private var trPendingApply = false
    private var trSnapUseSelection = false
    private var trSnapSelected = ""
    private var trSnapBefore = ""
    private var trSnapAfter = ""

    // Tự sửa chính tả
    private var scSession: SpellCheckerSession? = null
    private var acWord: String? = null
    private var acWordLen: Int = 0
    private var acSeparator: String = " "

    // Nhập liệu giọng nói
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceMode: Int = 0 // 0: không, 1: VI, 2: VI->EN
    private var voiceStatus: String? = null

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
        val p = prefs()
        vietnamese = p.getBoolean("vietnamese", true)
        loadProfileSettings()
    }

    override fun onCreateInputView(): View {
        container = FrameLayout(this)
        popup = TextView(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            elevation = dp(8).toFloat()
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(root)
        container.addView(popup, FrameLayout.LayoutParams(dp(56), dp(64)))
        rebuild()
        return container
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
        stopVoice(resetStatus = false)
        hidePopup()
        finishTelex()
        resetTranslateState()
        super.onFinishInputView(finishingInput)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        loadProfileSettings()
        rebuild()
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
        if (!telex.isEmpty &&
            (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            finishTelex()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { scSession?.close() } catch (_: Exception) {}
        scSession = null
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("samkeyboard", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- hồ sơ theo màn hình

    private fun screenWidthDp(): Int = resources.configuration.screenWidthDp
    private fun screenHeightDp(): Int = resources.configuration.screenHeightDp
    private fun isWideScreen(): Boolean = screenWidthDp() >= 600
    private fun profile(): String = if (isWideScreen()) "wide" else "narrow"
    private fun pkey(name: String): String = profile() + "_" + name

    private fun loadProfileSettings() {
        val p = prefs()
        val pf = profile()
        fun ig(name: String, old: String, def: Int): Int = p.getInt("${pf}_$name", p.getInt(old, def))
        fun sg(name: String, old: String, def: String): String = p.getString("${pf}_$name", p.getString(old, def)) ?: def
        fun bg(name: String, old: String, def: Boolean): Boolean = p.getBoolean("${pf}_$name", p.getBoolean(old, def))

        wideLayoutPref = sg("layout", "kb_wide_layout", "split")
        keyHeightPref = ig("height", "kb_height", 52)
        floatMarginStartDp = ig("fx", "kb_fx", 8)
        floatMarginBottomDp = ig("fy", "kb_fy", 8)
        floatWidthDp = ig("fw", "kb_fw", 340)
        kbStyle = sg("style", "kb_style", "iphone")
        kbColorIndex = ig("color", "kb_color", 0).coerceIn(0, kbColors.lastIndex)
        sideInsetDp = ig("inset", "kb_side_inset", 0)
        keyHGapDp = ig("hgap", "kb_h_gap", 3)
        keyVGapDp = ig("vgap", "kb_v_gap", 4)
        soundMode = ig("sound", "kb_sound", 1).coerceIn(0, soundModes.lastIndex)
        autoCorrect = bg("autocorrect", "kb_autocorrect", true)
    }

    private fun saveInt(name: String, value: Int) {
        prefs().edit().putInt(pkey(name), value).apply()
    }

    private fun saveString(name: String, value: String) {
        prefs().edit().putString(pkey(name), value).apply()
    }

    private fun saveBool(name: String, value: Boolean) {
        prefs().edit().putBoolean(pkey(name), value).apply()
    }

    // ---------------------------------------------------------------- tối ưu màn hình (gập/mở)

    private fun effectiveLayout(): KbLayout {
        if (!isWideScreen()) return KbLayout.NORMAL
        return when (wideLayoutPref) {
            "floating" -> KbLayout.FLOATING
            "normal" -> KbLayout.NORMAL
            else -> KbLayout.SPLIT
        }
    }

    private fun effectiveKeyHeight(): Int {
        val mode = effectiveLayout()
        val adj = when {
            mode == KbLayout.SPLIT -> 4
            isWideScreen() -> 2
            else -> -2
        }
        return (keyHeightPref + adj).coerceIn(36, 78)
    }

    private fun sidePadDp(): Int {
        val base = when (effectiveLayout()) {
            KbLayout.FLOATING -> 2
            KbLayout.SPLIT -> 6
            else -> if (screenWidthDp() < 380) 2 else 3
        }
        return (base + sideInsetDp).coerceAtLeast(0)
    }

    private fun currentFloatWidthDp(): Int {
        val sw = screenWidthDp()
        val maxW = (sw - 12).coerceAtLeast(240)
        val minW = 260.coerceAtMost(maxW)
        return floatWidthDp.coerceIn(minW, maxW)
    }

    private fun applyRootLayout() {
        if (!::root.isInitialized || !::container.isInitialized) return
        val lp = (root.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        val mode = effectiveLayout()
        val sw = screenWidthDp()
        if (mode == KbLayout.FLOATING) {
            val w = dp(currentFloatWidthDp())
            val maxStart = (sw - pxToDp(w) - 4).coerceAtLeast(0)
            floatMarginStartDp = floatMarginStartDp.coerceIn(0, maxStart)
            val maxBottom = (screenHeightDp() - 120).coerceAtLeast(0)
            floatMarginBottomDp = floatMarginBottomDp.coerceIn(0, maxBottom)
            lp.width = w
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.BOTTOM or Gravity.START
            lp.leftMargin = dp(floatMarginStartDp)
            lp.rightMargin = 0
            lp.bottomMargin = dp(floatMarginBottomDp)
            root.setPadding(dp(3), dp(4), dp(3), dp(6))
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.bottomMargin = 0
            val sp = dp(sidePadDp())
            root.setPadding(sp, dp(4), sp, dp(8))
        }
        root.layoutParams = lp
    }

    private fun pxToDp(px: Int): Int =
        (px / resources.displayMetrics.density).roundToInt()

    // ---------------------------------------------------------------- bố cục phím

    private fun t(s: String) = Key(Kind.TEXT, s)
    private fun gap(w: Float = 3.0f) = Key(Kind.SPACER, weight = w)

    private fun splitGapWeight(): Float = if (screenWidthDp() >= 720) 3.5f else 2.8f

    private fun normalLayout(): List<List<Key>> {
        val bottomLetters = listOf(
            Key(Kind.TO_SYMBOLS_1, "123", 1.25f),
            Key(Kind.LANG, "", 1.0f),
            Key(Kind.SPACE, "", 5.0f),
            Key(Kind.ENTER, "return", 1.75f),
        )
        val bottomSymbols = listOf(
            Key(Kind.TO_LETTERS, "ABC", 1.25f),
            Key(Kind.LANG, "", 1.0f),
            Key(Kind.SPACE, "", 5.0f),
            Key(Kind.ENTER, "return", 1.75f),
        )
        return when (layer) {
            Layer.LETTERS -> {
                val rows = mutableListOf<List<Key>>()
                // Dãy số trên màn hình trong (rộng)
                if (isWideScreen()) {
                    rows.add(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { t(it) })
                }
                rows.add("qwertyuiop".map { t(it.toString()) })
                rows.add(
                    listOf(Key(Kind.SPACER, weight = 0.5f)) +
                        "asdfghjkl".map { t(it.toString()) } +
                        listOf(Key(Kind.SPACER, weight = 0.5f))
                )
                rows.add(
                    listOf(Key(Kind.SHIFT, "⇧", 1.25f)) +
                        "zxcvbnm".map { t(it.toString()) } +
                        listOf(Key(Kind.DELETE, "⌫", 1.25f))
                )
                rows.add(bottomLetters)
                rows
            }
            Layer.SYMBOLS_1 -> listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { t(it) },
                listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"").map { t(it) },
                listOf(Key(Kind.TO_SYMBOLS_2, "#+=", 1.25f)) +
                    listOf(".", ",", "?", "!", "'").map { t(it) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.25f)),
                bottomSymbols,
            )
            Layer.SYMBOLS_2 -> listOf(
                listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { t(it) },
                listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { t(it) },
                listOf(Key(Kind.TO_SYMBOLS_1, "123", 1.25f)) +
                    listOf(".", ",", "?", "!", "'").map { t(it) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.25f)),
                bottomSymbols,
            )
        }
    }

    /** Bàn phím tách đôi kiểu Gboard (màn hình trong): dãy số, G/V lặp, dấu cách liền 1 phím. */
    private fun splitLayout(): List<List<Key>> {
        val g = splitGapWeight()
        val spaceW = 5.0f + g // dấu cách nối dài chung 1 phím trên giao diện màn hình trong
        val bottomLetters = listOf(
            Key(Kind.TO_SYMBOLS_1, "123", 1.25f),
            Key(Kind.LANG, "", 1.0f),
            Key(Kind.SPACE, "", spaceW),
            Key(Kind.ENTER, "return", 1.75f),
        )
        val bottomSymbols = listOf(
            Key(Kind.TO_LETTERS, "ABC", 1.25f),
            Key(Kind.LANG, "", 1.0f),
            Key(Kind.SPACE, "", spaceW),
            Key(Kind.ENTER, "return", 1.75f),
        )
        return when (layer) {
            Layer.LETTERS -> listOf(
                listOf("1", "2", "3", "4", "5").map { t(it) } + gap(g) +
                    listOf("6", "7", "8", "9", "0").map { t(it) },
                "qwert".map { t(it.toString()) } + gap(g) + "yuiop".map { t(it.toString()) },
                listOf(Key(Kind.SPACER, weight = 0.25f)) +
                    "asdfg".map { t(it.toString()) } + gap(g) +
                    "ghjkl".map { t(it.toString()) } + // G lặp ở đầu nửa phải (bên trái H)
                    listOf(Key(Kind.SPACER, weight = 0.25f)),
                listOf(Key(Kind.SHIFT, "⇧", 1.25f)) +
                    "zxcv".map { t(it.toString()) } + gap(g) + // V lặp ở cuối nửa trái (bên phải C)
                    "vbnm".map { t(it.toString()) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.25f)),
                bottomLetters,
            )
            Layer.SYMBOLS_1 -> listOf(
                listOf("1", "2", "3", "4", "5").map { t(it) } + gap(g) +
                    listOf("6", "7", "8", "9", "0").map { t(it) },
                listOf("-", "/", ":", ";", "(").map { t(it) } + gap(g) +
                    listOf(")", "$", "&", "@", "\"").map { t(it) },
                listOf(Key(Kind.TO_SYMBOLS_2, "#+=", 1.25f)) +
                    listOf(".", ",").map { t(it) } + gap(g) +
                    listOf("?", "!", "'").map { t(it) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.25f)),
                bottomSymbols,
            )
            Layer.SYMBOLS_2 -> listOf(
                listOf("[", "]", "{", "}", "#").map { t(it) } + gap(g) +
                    listOf("%", "^", "*", "+", "=").map { t(it) },
                listOf("_", "\\", "|", "~", "<").map { t(it) } + gap(g) +
                    listOf(">", "€", "£", "¥", "•").map { t(it) },
                listOf(Key(Kind.TO_SYMBOLS_1, "123", 1.25f)) +
                    listOf(".", ",").map { t(it) } + gap(g) +
                    listOf("?", "!", "'").map { t(it) } +
                    listOf(Key(Kind.DELETE, "⌫", 1.25f)),
                bottomSymbols,
            )
        }
    }

    private fun activeLayout(): List<List<Key>> =
        if (effectiveLayout() == KbLayout.SPLIT) splitLayout() else normalLayout()

    // ---------------------------------------------------------------- màu sắc & giao diện

    private fun currentColor(): KbColor = kbColors[kbColorIndex.coerceIn(kbColors.indices)]

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun colors(): Colors {
        val dark = isDark()
        val accent = currentColor().accent
        return if (kbStyle == "samsung") samsungColors(dark, accent) else iphoneColors(dark, accent)
    }

    private fun iphoneColors(dark: Boolean, accent: Int): Colors {
        val kc = currentColor()
        return if (dark) {
            Colors(
                background = if (kc.darkTint != 0) kc.darkTint else Color.parseColor("#1C1C1E"),
                key = Color.parseColor("#636366"),
                special = Color.parseColor("#48484A"),
                pressedKey = Color.parseColor("#8E8E93"),
                pressedSpecial = Color.parseColor("#636366"),
                text = Color.WHITE,
                specialText = Color.WHITE,
                accent = accent,
                popupBg = Color.parseColor("#636366"),
                popupText = Color.WHITE,
                panel = Color.parseColor("#2C2C2E"),
                keyStroke = 0,
            )
        } else {
            val baseBg = Color.parseColor("#D1D3D9")
            val bg = if (kc.lightTint != 0) blend(baseBg, kc.lightTint, 0.25f) else baseBg
            Colors(
                background = bg,
                key = Color.WHITE,
                special = Color.parseColor("#ADB3BC"),
                pressedKey = Color.parseColor("#BFC3C9"),
                pressedSpecial = Color.parseColor("#8E959D"),
                text = Color.BLACK,
                specialText = Color.BLACK,
                accent = accent,
                popupBg = Color.WHITE,
                popupText = Color.BLACK,
                panel = Color.parseColor("#E5E6EA"),
                keyStroke = 0,
            )
        }
    }

    private fun samsungColors(dark: Boolean, accent: Int): Colors {
        val kc = currentColor()
        return if (dark) {
            val baseBg = Color.parseColor("#010101")
            val bg = if (kc.darkTint != 0) blend(baseBg, kc.darkTint, 0.35f) else baseBg
            Colors(
                background = bg,
                key = Color.parseColor("#3A3A3C"),
                special = Color.parseColor("#2C2C2E"),
                pressedKey = Color.parseColor("#555557"),
                pressedSpecial = Color.parseColor("#444446"),
                text = Color.parseColor("#F5F5F5"),
                specialText = Color.parseColor("#F5F5F5"),
                accent = accent,
                popupBg = Color.parseColor("#4A4A4C"),
                popupText = Color.WHITE,
                panel = Color.parseColor("#1C1C1E"),
                keyStroke = 0,
            )
        } else {
            val baseBg = Color.parseColor("#F0F0F0")
            val bg = if (kc.lightTint != 0) blend(baseBg, kc.lightTint, 0.20f) else baseBg
            Colors(
                background = bg,
                key = Color.WHITE,
                special = Color.parseColor("#E1E3E6"),
                pressedKey = Color.parseColor("#D5D7DA"),
                pressedSpecial = Color.parseColor("#C8CBD0"),
                text = Color.parseColor("#191919"),
                specialText = Color.parseColor("#191919"),
                accent = accent,
                popupBg = Color.WHITE,
                popupText = Color.parseColor("#191919"),
                panel = Color.parseColor("#E6E8EB"),
                keyStroke = Color.parseColor("#E0E0E0"),
            )
        }
    }

    private fun blend(base: Int, tint: Int, amount: Float): Int {
        if (tint == 0) return base
        val inv = 1f - amount
        val r = (Color.red(base) * inv + Color.red(tint) * amount).roundToInt().coerceIn(0, 255)
        val g = (Color.green(base) * inv + Color.green(tint) * amount).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(base) * inv + Color.blue(tint) * amount).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun keyCornerDp(): Int = if (kbStyle == "samsung") 8 else 5
    private fun panelCornerDp(): Int = if (kbStyle == "samsung") 10 else 8

    // ---------------------------------------------------------------- dựng giao diện

    private fun rebuild() {
        if (!::root.isInitialized) return
        val c = colors()
        applyRootLayout()
        root.removeAllViews()
        root.setBackgroundColor(c.background)

        if (showKbSettings) root.addView(buildAdjustBar(c))
        root.addView(buildToolbar(c))

        val rowH = effectiveKeyHeight()
        val textScale = (rowH / 52f).coerceIn(0.85f, 1.2f)
        val rowBottom = if (keyVGapDp <= 1) 0 else 1
        for (row in activeLayout()) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(rowH),
                ).apply { bottomMargin = dp(rowBottom) }
            }
            for (key in row) rowView.addView(makeKeyView(key, c, textScale))
            root.addView(rowView)
        }
        popup.setTextColor(c.popupText)
        popup.background = rounded(c.popupBg, 10)
    }

    private fun buildAdjustBar(c: Colors): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.panel)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val wide = isWideScreen()
        val eff = effectiveLayout()

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38),
            )
        }
        if (wide) {
            row1.addView(chip("Bình thường", 1.2f, c, accent = eff == KbLayout.NORMAL) { setWideLayout("normal") })
            row1.addView(chip("Tách đôi", 1.1f, c, accent = eff == KbLayout.SPLIT) { setWideLayout("split") })
            row1.addView(chip("Nổi", 0.8f, c, accent = eff == KbLayout.FLOATING) { setWideLayout("floating") })
        } else {
            row1.addView(labelChip("Màn hình ngoài (riêng)", 3.1f, c))
        }
        row1.addView(chip("Đóng", 0.8f, c) {
            showKbSettings = false
            rebuild()
        })
        bar.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36),
            )
        }
        row2.addView(labelChip("Cao", 0.6f, c))
        row2.addView(chip("−", 0.45f, c) { adjustHeight(-2) })
        row2.addView(labelChip("${effectiveKeyHeight()}", 0.45f, c))
        row2.addView(chip("+", 0.45f, c) { adjustHeight(2) })
        row2.addView(chip("Âm: ${soundModes[soundMode]}", 1.4f, c) { cycleSound() })
        row2.addView(chip(if (autoCorrect) "Sửa lỗi: Bật" else "Sửa lỗi: Tắt", 1.3f, c, accent = autoCorrect) { toggleAutoCorrect() })
        bar.addView(row2)

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36),
            )
        }
        val styleLabel = if (kbStyle == "samsung") "Giao diện: Samsung" else "Giao diện: iPhone"
        row3.addView(chip(styleLabel, 1.4f, c) { cycleStyle() })
        row3.addView(chip("Màu: ${currentColor().name}", 1.2f, c) { cycleColor() })
        val tuneLabel = if (showDeepTune) "Tinh chỉnh ▲" else "Tinh chỉnh ▼"
        row3.addView(chip(tuneLabel, 1.0f, c, accent = showDeepTune) {
            showDeepTune = !showDeepTune
            rebuild()
        })
        bar.addView(row3)

        if (showDeepTune) {
            val row4 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36),
                )
            }
            row4.addView(labelChip("Lề", 0.5f, c))
            row4.addView(chip("−", 0.45f, c) { adjustSideInset(-2) })
            row4.addView(labelChip("$sideInsetDp", 0.4f, c))
            row4.addView(chip("+", 0.45f, c) { adjustSideInset(2) })
            row4.addView(labelChip("Ngang", 0.6f, c))
            row4.addView(chip("−", 0.45f, c) { adjustHGap(-1) })
            row4.addView(labelChip("$keyHGapDp", 0.35f, c))
            row4.addView(chip("+", 0.45f, c) { adjustHGap(1) })
            row4.addView(labelChip("Dọc", 0.5f, c))
            row4.addView(chip("−", 0.45f, c) { adjustVGap(-1) })
            row4.addView(labelChip("$keyVGapDp", 0.35f, c))
            row4.addView(chip("+", 0.45f, c) { adjustVGap(1) })
            bar.addView(row4)

            val row5 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(30),
                )
            }
            val screenLabel = if (wide) "Đang chỉnh: Màn hình trong (riêng)" else "Đang chỉnh: Màn hình ngoài (riêng)"
            row5.addView(labelChip("$screenLabel — Lề: mép→giữa, Ngang/Dọc: khoảng cách phím", 4f, c))
            bar.addView(row5)
        }

        if (eff == KbLayout.FLOATING) {
            val drag = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34),
                )
                text = "⠿  Kéo để di chuyển bàn phím"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(c.text)
                setBackgroundColor(adjustAlpha(c.special, 180))
                setOnTouchListener(DragTouchListener())
            }
            bar.addView(drag)

            val nudge = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(34),
                )
            }
            nudge.addView(chip("←", 1f, c) { nudgeFloat(-12, 0) })
            nudge.addView(chip("→", 1f, c) { nudgeFloat(12, 0) })
            nudge.addView(chip("↑", 1f, c) { nudgeFloat(0, 12) })
            nudge.addView(chip("↓", 1f, c) { nudgeFloat(0, -12) })
            nudge.addView(chip("Rộng −", 1f, c) { adjustFloatWidth(-12) })
            nudge.addView(chip("Rộng +", 1f, c) { adjustFloatWidth(12) })
            bar.addView(nudge)
        }
        return bar
    }

    private fun cycleStyle() {
        kbStyle = if (kbStyle == "iphone") "samsung" else "iphone"
        saveString("style", kbStyle)
        rebuild()
    }

    private fun cycleColor() {
        kbColorIndex = (kbColorIndex + 1) % kbColors.size
        saveInt("color", kbColorIndex)
        rebuild()
    }

    private fun cycleSound() {
        soundMode = (soundMode + 1) % soundModes.size
        saveInt("sound", soundMode)
        rebuild()
    }

    private fun toggleAutoCorrect() {
        autoCorrect = !autoCorrect
        saveBool("autocorrect", autoCorrect)
        rebuild()
    }

    private fun setWideLayout(mode: String) {
        wideLayoutPref = mode
        saveString("layout", mode)
        rebuild()
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startMarginStart = 0
        private var startMarginBottom = 0
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    startMarginStart = floatMarginStartDp
                    startMarginBottom = floatMarginBottomDp
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dxDp = pxToDp((ev.rawX - startRawX).roundToInt())
                    val dyDp = pxToDp((ev.rawY - startRawY).roundToInt())
                    floatMarginStartDp = startMarginStart + dxDp
                    floatMarginBottomDp = startMarginBottom - dyDp
                    saveFloatState()
                    applyRootLayout()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun nudgeFloat(dx: Int, dy: Int) {
        floatMarginStartDp += dx
        floatMarginBottomDp += dy
        saveFloatState()
        applyRootLayout()
    }

    private fun adjustFloatWidth(delta: Int) {
        floatWidthDp += delta
        saveInt("fw", floatWidthDp)
        applyRootLayout()
    }

    private fun saveFloatState() {
        val p = prefs().edit()
        p.putInt(pkey("fx"), floatMarginStartDp)
        p.putInt(pkey("fy"), floatMarginBottomDp)
        p.apply()
    }

    private fun adjustHeight(delta: Int) {
        keyHeightPref = (keyHeightPref + delta).coerceIn(36, 78)
        saveInt("height", keyHeightPref)
        rebuild()
    }

    private fun adjustSideInset(delta: Int) {
        sideInsetDp = (sideInsetDp + delta).coerceIn(0, 60)
        saveInt("inset", sideInsetDp)
        rebuild()
    }

    private fun adjustHGap(delta: Int) {
        keyHGapDp = (keyHGapDp + delta).coerceIn(0, 10)
        saveInt("hgap", keyHGapDp)
        rebuild()
    }

    private fun adjustVGap(delta: Int) {
        keyVGapDp = (keyVGapDp + delta).coerceIn(0, 10)
        saveInt("vgap", keyVGapDp)
        rebuild()
    }

    private fun adjustAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun displayLabel(key: Key): String =
        if (key.kind == Kind.TEXT && layer == Layer.LETTERS && shift != Shift.OFF) {
            key.label.uppercase()
        } else key.label

    private fun enterLabel(): String {
        val info = currentInputEditorInfo ?: return "return"
        val options = info.imeOptions
        val action = options and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noEnterAction) return "return"
        return when (action) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Tìm"
            EditorInfo.IME_ACTION_SEND -> "Gửi"
            EditorInfo.IME_ACTION_NEXT -> "Tiếp"
            EditorInfo.IME_ACTION_DONE -> "Xong"
            else -> "return"
        }
    }

    private fun keyLabel(key: Key): String = when {
        key.kind == Kind.SHIFT && shift == Shift.LOCK -> "⇪"
        key.kind == Kind.SHIFT -> "⇧"
        key.kind == Kind.LANG -> if (vietnamese) "VI" else "EN"
        key.kind == Kind.SPACE -> "space"
        key.kind == Kind.ENTER -> enterLabel()
        else -> displayLabel(key)
    }

    private fun makeKeyView(key: Key, c: Colors, textScale: Float): View {
        val hMargin = keyHGapDp.coerceAtLeast(0)
        val vMargin = keyVGapDp.coerceAtLeast(0)
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, key.weight)
            .apply { setMargins(dp(hMargin), dp(vMargin), dp(hMargin), dp(vMargin)) }

        if (key.kind == Kind.SPACER) {
            return View(this).apply { layoutParams = params }
        }

        val isEnter = key.kind == Kind.ENTER
        val isSpecial = key.kind != Kind.TEXT && key.kind != Kind.SPACE
        val baseColor = when {
            isEnter -> c.accent
            isSpecial -> c.special
            else -> c.key
        }
        val pressedColor = when {
            isEnter -> lightenPress(c.accent)
            isSpecial -> c.pressedSpecial
            else -> c.pressedKey
        }
        val textColor = if (isEnter) Color.WHITE else c.text
        val radius = keyCornerDp()

        return TextView(this).apply {
            layoutParams = params
            text = keyLabel(key)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            typeface = if (key.kind == Kind.TEXT) Typeface.DEFAULT else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                when (key.kind) {
                    Kind.TEXT -> 22f * textScale
                    Kind.SPACE -> 15f * textScale
                    Kind.ENTER -> 15f * textScale
                    else -> 17f * textScale
                },
            )
            background = rounded(baseColor, radius, c.keyStroke)
            val elev = if (key.kind == Kind.TEXT || key.kind == Kind.SPACE) dp(1).toFloat() else 0f
            elevation = elev
            isClickable = true
            isHapticFeedbackEnabled = true
            setOnTouchListener { v, ev -> handleTouch(v, ev, key, baseColor, pressedColor, radius, c.keyStroke) }
        }
    }

    private fun lightenPress(accent: Int): Int {
        val r = (Color.red(accent) + (255 - Color.red(accent)) * 0.25f).roundToInt().coerceIn(0, 255)
        val g = (Color.green(accent) + (255 - Color.green(accent)) * 0.25f).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(accent) + (255 - Color.blue(accent)) * 0.25f).roundToInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun rounded(color: Int, radiusDp: Int = 5, stroke: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != 0) setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun handleTouch(
        v: View, ev: MotionEvent, key: Key, baseColor: Int, pressedColor: Int, radius: Int, stroke: Int,
    ): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.background = rounded(pressedColor, radius, 0)
                performKeyHaptic(v)
                playKeySound(key)
                if (key.kind == Kind.TEXT) showPopup(v, keyLabel(key))
                if (key.kind == Kind.DELETE) {
                    deleteOnce()
                    handler.postDelayed(repeatDelete, 400)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = ev.x >= 0 && ev.x <= v.width && ev.y >= 0 && ev.y <= v.height
                v.background = rounded(if (inside) pressedColor else baseColor, radius, if (inside) 0 else stroke)
                if (!inside) hidePopup()
            }
            MotionEvent.ACTION_UP -> {
                v.background = rounded(baseColor, radius, stroke)
                hidePopup()
                if (key.kind == Kind.DELETE) {
                    handler.removeCallbacks(repeatDelete)
                    refreshShift()
                } else {
                    val inside = ev.x >= 0 && ev.x <= v.width && ev.y >= 0 && ev.y <= v.height
                    if (inside) onKey(key)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                v.background = rounded(baseColor, radius, stroke)
                hidePopup()
                handler.removeCallbacks(repeatDelete)
            }
        }
        return true
    }

    private fun performKeyHaptic(v: View) {
        val ok = if (Build.VERSION.SDK_INT >= 27) {
            v.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            ) || v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (!ok) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun playKeySound(key: Key) {
        if (soundMode == 0) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val baseFx = when (key.kind) {
            Kind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            Kind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            Kind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        val (fx, vol) = when (soundMode) {
            2 -> baseFx to 0.35f
            3 -> AudioManager.FX_KEY_CLICK to 0.8f
            4 -> baseFx to 1.0f
            else -> baseFx to 0.65f
        }
        try { am.playSoundEffect(fx, vol) } catch (_: Exception) {}
    }

    private fun showPopup(anchor: View, text: String) {
        if (!::popup.isInitialized || !::container.isInitialized) return
        popup.animate().cancel()
        popup.text = text
        popup.alpha = 0f
        popup.scaleX = 0.6f
        popup.scaleY = 0.6f
        popup.translationY = dp(6).toFloat()
        popup.visibility = View.VISIBLE
        popup.bringToFront()

        val lp = popup.layoutParams as FrameLayout.LayoutParams
        lp.width = dp(if (text.length <= 1) 58 else 74)
        lp.height = dp(66)
        val locA = IntArray(2)
        val locC = IntArray(2)
        anchor.getLocationInWindow(locA)
        container.getLocationInWindow(locC)
        val ax = locA[0] - locC[0] + anchor.width / 2 - lp.width / 2
        val ay = locA[1] - locC[1] - lp.height - dp(4)
        val cw = if (container.width > 0) container.width else resources.displayMetrics.widthPixels
        lp.leftMargin = ax.coerceIn(dp(2), (cw - lp.width - dp(2)).coerceAtLeast(dp(2)))
        lp.topMargin = ay.coerceAtLeast(dp(2))
        popup.layoutParams = lp

        popup.pivotX = lp.width / 2f
        popup.pivotY = lp.height.toFloat()
        popup.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hidePopup() {
        if (!::popup.isInitialized) return
        if (popup.visibility != View.VISIBLE) {
            popup.animate().cancel()
            return
        }
        popup.animate().cancel()
        popup.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                popup.visibility = View.GONE
                popup.alpha = 1f
                popup.scaleX = 1f
                popup.scaleY = 1f
            }
            .start()
    }

    // ---------------------------------------------------------------- gõ tiếng Việt (Telex, QWERTY)

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

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        if (telex.isEmpty) {
            ic.commitText("", 1)
        } else {
            ic.setComposingText(telex.text(), 1)
        }
    }

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
        if (!telex.isEmpty) finishTelex()
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        var sep = " "
        var doAutoCorrect = autoCorrect

        if (now - lastSpaceTap < 400 && before.length >= 2 &&
            before.last() == ' ' && before[before.length - 2].isLetterOrDigit()
        ) {
            // double-space -> ". "
            val word = extractLastWord(before.dropLast(1))
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTap = 0
            sep = ". "
            if (doAutoCorrect) requestAutoCorrect(word, sep)
        } else {
            val word = extractLastWord(before)
            ic.commitText(" ", 1)
            lastSpaceTap = now
            if (doAutoCorrect) requestAutoCorrect(word, sep)
        }
        refreshShift()
    }

    private fun extractLastWord(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        var i = text.length - 1
        while (i >= 0) {
            val c = text[i]
            if (c.isLetter() || c == '\'' || c.code > 127) {
                sb.append(c)
                i--
            } else {
                break
            }
        }
        return sb.reverse().toString()
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

    // ---------------------------------------------------------------- tự sửa chính tả

    private fun requestAutoCorrect(word: String, separator: String) {
        if (!autoCorrect) return
        if (word.length < 2) return
        // bỏ qua nếu toàn ký tự không phải chữ latin cơ bản (tránh phá tiếng Việt có dấu phức tạp đã đúng)
        // nhưng vẫn thử với spell checker nếu có; yêu cầu tối thiểu: có chữ cái
        acWord = word
        acWordLen = word.length
        acSeparator = separator
        val locale = if (vietnamese) Locale("vi", "VN") else Locale("en", "US")
        try {
            if (scSession == null) {
                val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
                scSession = tsm?.newSpellCheckerSession(null, locale, this, true)
            }
            scSession?.getSuggestions(TextInfo(word), 3)
        } catch (_: Exception) {
        }
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        val word = acWord ?: return
        val sep = acSeparator
        val len = acWordLen
        acWord = null
        if (results == null || results.isEmpty()) return
        val info = results[0]
        val attrs = info.suggestionsAttributes
        val inDict = (attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) != 0
        val looksTypo = (attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
        val hasRecommended = (attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS) != 0
        if (inDict && !looksTypo) return
        if (info.suggestionsCount <= 0) return
        val suggestion = info.getSuggestionAt(0) ?: return
        if (suggestion.isBlank() || suggestion.equals(word, ignoreCase = true)) return
        // chỉ tự sửa khi chắc chắn là lỗi gõ hoặc có gợi ý khuyến nghị
        if (!looksTypo && !hasRecommended) return

        handler.post {
            val ic = currentInputConnection ?: return@post
            val before = ic.getTextBeforeCursor(len + sep.length + 2, 0)?.toString().orEmpty()
            if (before.endsWith(word + sep)) {
                val replacement = matchCase(word, suggestion)
                ic.deleteSurroundingText(len + sep.length, 0)
                ic.commitText(replacement + sep, 1)
            }
        }
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        // không dùng
    }

    private fun matchCase(original: String, suggestion: String): String {
        if (original.isEmpty() || suggestion.isEmpty()) return suggestion
        return when {
            original.all { it.isUpperCase() || !it.isLetter() } -> suggestion.uppercase(Locale.getDefault())
            original[0].isUpperCase() -> suggestion.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
            else -> suggestion.lowercase(Locale.getDefault())
        }
    }

    // ---------------------------------------------------------------- nhập liệu giọng nói

    private fun hasRecordAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun startVoice(mode: Int) {
        if (voiceMode != 0) {
            stopVoice(resetStatus = true)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceStatus("Không hỗ trợ giọng nói", 2000)
            return
        }
        if (!hasRecordAudioPermission()) {
            showVoiceStatus("Cần cấp quyền Micro", 2500)
            // thử mở cài đặt quyền của app (từ IME có thể không mở được, nhưng cứ thử)
            try {
                val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            } catch (_: Exception) {}
            return
        }
        finishTelex()
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    showVoiceStatus(if (mode == 2) "Nghe (VI→EN)..." else "Đang nghe...", 0)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    showVoiceStatus(if (mode == 2) "Đang dịch..." else "Đang xử lý...", 0)
                }
                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận dạng được"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi mạng"
                        SpeechRecognizer.ERROR_AUDIO -> "Lỗi âm thanh"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Cần quyền Micro"
                        else -> "Lỗi giọng nói"
                    }
                    voiceMode = 0
                    showVoiceStatus(msg, 2000)
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    val m = voiceMode
                    voiceMode = 0
                    if (text.isBlank()) {
                        showVoiceStatus("Không nhận dạng được", 2000)
                        return
                    }
                    if (m == 2) {
                        showVoiceStatus("Đang dịch...", 0)
                        translateVoiceToEnglish(text)
                    } else {
                        voiceStatus = null
                        commitTextToApp(text)
                        rebuild()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            voiceMode = mode
            speechRecognizer?.startListening(intent)
            showVoiceStatus(if (mode == 2) "Nghe (VI→EN)..." else "Đang nghe...", 0)
        } catch (e: Exception) {
            voiceMode = 0
            showVoiceStatus("Không thể mở micro", 2000)
        }
    }

    private fun stopVoice(resetStatus: Boolean) {
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        voiceMode = 0
        if (resetStatus) {
            voiceStatus = null
            rebuild()
        }
    }

    private fun showVoiceStatus(msg: String?, clearAfterMs: Long = 0) {
        voiceStatus = msg
        rebuild()
        if (clearAfterMs > 0 && msg != null) {
            val snapshot = msg
            handler.postDelayed({
                if (voiceStatus == snapshot && voiceMode == 0) {
                    voiceStatus = null
                    rebuild()
                }
            }, clearAfterMs)
        }
    }

    private fun translateVoiceToEnglish(text: String) {
        Thread {
            var out: String? = null
            var err = "Lỗi dịch"
            try {
                out = requestTranslation(text, "en")
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
            } catch (_: Exception) {
                err = "Lỗi dịch"
            }
            handler.post {
                voiceStatus = null
                if (!out.isNullOrEmpty()) {
                    commitTextToApp(out)
                } else {
                    // nếu dịch lỗi, vẫn chèn nguyên văn tiếng Việt để không mất nội dung
                    commitTextToApp(text)
                    showVoiceStatus(err, 2000)
                    return@post
                }
                rebuild()
            }
        }.start()
    }

    private fun commitTextToApp(text: String) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        finishTelex()
        ic.commitText(text, 1)
    }

    // ---------------------------------------------------------------- tự viết hoa

    private fun capsMode(): Int {
        val ic = currentInputConnection ?: return 0
        val inputType = currentInputEditorInfo?.inputType ?: 0
        return ic.getCursorCapsMode(inputType)
    }

    private fun refreshShift(forceRebuild: Boolean = false) {
        val before = shift
        if (layer == Layer.LETTERS && shift == Shift.OFF && capsMode() != 0) {
            shift = Shift.ON
        }
        if (shift != before || forceRebuild) rebuild()
    }

    // ---------------------------------------------------------------- bảng dịch

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
        trPendingApply = false
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
        trPendingApply = false
        translateOpen = true
        layer = Layer.LETTERS
        rebuild()
        if (source.isNotBlank()) {
            translateNow(auto = false)
        }
    }

    private fun closeTranslate() {
        finishTrTelex()
        handler.removeCallbacks(trAutoTranslate)
        translateJob++
        translateOpen = false
        trTranslating = false
        trPendingApply = false
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
                translateNow(auto = false)
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

    private fun scheduleTrAutoTranslate() {
        handler.removeCallbacks(trAutoTranslate)
        if (translateOpen && trSourceText().isNotBlank()) {
            handler.postDelayed(trAutoTranslate, 700)
        }
    }

    private fun buildToolbar(c: Colors): View {
        if (!translateOpen) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38),
                )
            }
            bar.addView(chip("Dịch", 1.1f, c) { openTranslate() })
            val micViLabel = if (voiceMode == 1) "⏹" else "🎤"
            val micEnLabel = if (voiceMode == 2) "⏹" else "VI→EN"
            bar.addView(chip(micViLabel, 0.8f, c, accent = voiceMode == 1) { startVoice(1) })
            bar.addView(chip(micEnLabel, 1.0f, c, accent = voiceMode == 2) { startVoice(2) })
            if (voiceStatus != null) {
                bar.addView(labelChip(voiceStatus!!, 1.6f, c))
            } else {
                bar.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f)
                })
            }
            bar.addView(chip("⌨", 0.8f, c) {
                showKbSettings = !showKbSettings
                rebuild()
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38),
            )
        }
        control.addView(chip("Sang: " + targets[targetIndex].name, 2.4f, c) {
            targetIndex = (targetIndex + 1) % targets.size
            rebuild()
            if (trSourceText().isNotBlank()) translateNow(auto = false)
        })
        val actionLabel = when {
            trTranslating -> "Đang dịch..."
            translateStatus != null && trResult.isEmpty() -> translateStatus!!
            else -> "Dịch"
        }
        control.addView(chip(actionLabel, 1.2f, c, accent = true) {
            translateNow(auto = false)
        })
        control.addView(chip("⌨", 0.7f, c) {
            showKbSettings = !showKbSettings
            rebuild()
        })
        control.addView(chip("✕", 0.7f, c) { closeTranslate() })
        panel.addView(control)

        val srcText = trSourceText()
        val srcView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40),
            ).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
            text = if (srcText.isEmpty()) "Nhập văn bản cần dịch..." else srcText
            setTextColor(if (srcText.isEmpty()) Color.GRAY else c.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(c.key, panelCornerDp())
            maxLines = 2
        }
        panel.addView(srcView)

        val resLabel = when {
            trTranslating -> "Đang dịch..."
            trResult.isNotEmpty() -> trResult
            translateStatus != null -> translateStatus!!
            else -> "Bản dịch sẽ hiện ở đây"
        }
        val resView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38),
            ).apply { setMargins(dp(3), dp(2), dp(3), dp(4)) }
            text = resLabel
            setTextColor(if (trResult.isNotEmpty() && !trTranslating) c.text else Color.GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(c.special, panelCornerDp())
            maxLines = 2
        }
        panel.addView(resView)

        return panel
    }

    private fun chip(
        label: String, weight: Float, c: Colors, accent: Boolean = false, onClick: () -> Unit,
    ): View = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            .apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(if (accent) Color.WHITE else c.text)
        background = rounded(if (accent) c.accent else c.special, panelCornerDp())
        isClickable = true
        setOnClickListener {
            performKeyHaptic(it)
            onClick()
        }
    }

    private fun labelChip(label: String, weight: Float, c: Colors): View = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            .apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(c.text)
        maxLines = 1
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
        trPendingApply = !auto
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
            } catch (_: Exception) {
                err = "Lỗi dịch"
            }
            handler.post { onTrTranslated(job, out, err) }
        }.start()
    }

    private fun onTrTranslated(job: Int, out: String?, err: String) {
        if (job != translateJob) return
        trTranslating = false
        val shouldApply = trPendingApply
        trPendingApply = false
        if (out.isNullOrEmpty()) {
            translateStatus = err
            trResult = ""
            rebuild()
        } else {
            trResult = out
            translateStatus = null
            if (shouldApply) {
                applyTranslation()
            } else {
                rebuild()
            }
        }
    }

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
                ic.commitText(trResult, 1)
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
