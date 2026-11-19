@file:Suppress("DEPRECATION")

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
import android.view.inputmethod.ExtractedTextRequest
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
 *  - Màn hình trong (rộng): tách đôi kiểu Gboard + dãy số, dấu cách liền, popup lớn, gõ 2 tay.
 *  - Màn hình ngoài (gập lại): bàn phím bình thường.
 *  - Tinh chỉnh (bố cục, chiều cao, lề, khoảng cách phím, theme, màu, âm, sửa lỗi, nổi)
 *    lưu RIÊNG BIỆT cho từng màn hình (trong/ngoài) và từng chiều (dọc/ngang).
 *  - Telex QWERTY (VI/EN), tự sửa chính tả khi bấm dấu cách.
 *  - Dịch: gõ văn bản nguồn trong bảng dịch, bản dịch hiện thẳng (trực tiếp) lên khung chat.
 *  - Giọng nói VI (hiện song song) và VI→EN (dịch & hiện lên khung chat; bấm ngắt thu âm sẽ dịch ngay).
 *  - Giao diện iPhone/Samsung, đổi màu, popup mượt, âm thanh gõ.
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

    // ----- Tùy chỉnh (lưu riêng: màn hình trong/ngoài × dọc/ngang) -----
    private var wideLayoutPref = "split"
    private var keyHeightPref = 52
    private var floatMarginStartDp = 8
    private var floatMarginBottomDp = 8
    private var floatWidthDp = 340
    private var showKbSettings = false
    private var showDeepTune = false
    private var kbStyle = "iphone"
    private var kbColorIndex = 0
    private var sideInsetDp = 0
    private var keyHGapDp = 3
    private var keyVGapDp = 4
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

    // Telex (VI/EN)
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
    private var trSourceBase = ""
    private val trSourceTelex = TelexEngine()
    private var trAnchorBefore = ""
    private var trAnchorAfter = ""
    private var trAppliedLen = 0
    private var trTranslating = false
    private var trFinalizing = false

    // Tự sửa chính tả
    private var scSession: SpellCheckerSession? = null
    private var scLocale: Locale? = null
    private var acWord: String? = null
    private var acWordLen: Int = 0
    private var acSeparator: String = " "

    private val commonFixes = mapOf(
        "teh" to "the", "adn" to "and", "ahve" to "have", "waht" to "what", "taht" to "that",
        "hte" to "the", "recieve" to "receive", "seperate" to "separate", "definately" to "definitely",
        "occured" to "occurred", "wierd" to "weird", "untill" to "until", "alot" to "a lot",
        "becuase" to "because", "goverment" to "government", "enviroment" to "environment",
        "freind" to "friend", "adress" to "address", "begining" to "beginning", "comming" to "coming",
        "tommorow" to "tomorrow", "neccessary" to "necessary", "accomodate" to "accommodate",
        "occurence" to "occurrence", "arguement" to "argument", "calender" to "calendar",
        "collegue" to "colleague", "concious" to "conscious", "dissapear" to "disappear",
        "existance" to "existence", "foriegn" to "foreign", "grammer" to "grammar",
        "harrass" to "harass", "independant" to "independent", "liason" to "liaison",
        "maintainance" to "maintenance", "noticable" to "noticeable", "posession" to "possession",
        "publically" to "publicly", "realy" to "really", "succesful" to "successful",
        "tounge" to "tongue", "truely" to "truly", "writting" to "writing",
        "dont" to "don't", "cant" to "can't", "wont" to "won't", "im" to "I'm",
        "ive" to "I've", "youre" to "you're", "theyre" to "they're", "didnt" to "didn't",
        "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't", "shouldnt" to "shouldn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "hasnt" to "hasn't", "havent" to "haven't",
        "co" to "có", "ko" to "không", "k" to "không", "dc" to "được", "đc" to "được",
        "kh" to "không", "ng" to "người", "bn" to "bạn", "mk" to "mình", "mik" to "mình",
        "vs" to "với", "r" to "rồi", "cx" to "cũng", "bik" to "biết", "bt" to "biết",
        "j" to "gì", "cj" to "chị", "ah" to "anh", "e" to "em", "uk" to "ừ", "uh" to "ừ",
    )

    // Nhập liệu giọng nói
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceMode: Int = 0 // 0: không, 1: VI, 2: VI→EN
    private var voiceStopping: Boolean = false
    private var voiceStatus: String? = null
    private var voiceComposing: Boolean = false
    private var voicePartialJob: Int = 0
    private var lastVoicePartialVi: String = ""
    private val voicePartialRunnable = Runnable { runVoicePartialTranslate() }
    private var voiceStopFallbackText: String = ""
    private val voiceStopFallbackRunnable = Runnable {
        if (voiceStopping && voiceMode == 2 && voiceStopFallbackText.isNotBlank()) {
            val text = voiceStopFallbackText
            voiceStopping = false
            voiceMode = 0
            if (voiceComposing) {
                try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                voiceComposing = false
            }
            showVoiceStatus("Đang dịch...", 0)
            translateVoiceFinal(text)
        }
    }

    private val repeatDelete = object : Runnable {
        override fun run() { deleteOnce(); handler.postDelayed(this, 45) }
    }

    private val trAutoTranslate = Runnable { translateNow(auto = true) }

    // ---------------------------------------------------------------- vòng đời

    override fun onCreate() {
        super.onCreate()
        vietnamese = prefs().getBoolean("vietnamese", true)
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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        stopVoice(resetStatus = false, cancel = true)
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
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
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

    // ---------------------------------------------------------------- hồ sơ theo màn hình & chiều

    private fun screenWidthDp(): Int = resources.configuration.screenWidthDp
    private fun screenHeightDp(): Int = resources.configuration.screenHeightDp
    private fun isWideScreen(): Boolean = screenWidthDp() >= 600
    private fun orientationTag(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
    private fun baseProfile(): String = if (isWideScreen()) "wide" else "narrow"
    private fun profile(): String = baseProfile() + "_" + orientationTag()
    private fun pkey(name: String): String = profile() + "_" + name

    private fun loadProfileSettings() {
        val p = prefs()
        val pf = profile()
        val base = baseProfile()
        fun ig(name: String, old: String, def: Int): Int =
            p.getInt("${pf}_$name", p.getInt("${base}_$name", p.getInt(old, def)))
        fun sg(name: String, old: String, def: String): String =
            p.getString("${pf}_$name", p.getString("${base}_$name", p.getString(old, def))) ?: def
        fun bg(name: String, old: String, def: Boolean): Boolean =
            p.getBoolean("${pf}_$name", p.getBoolean("${base}_$name", p.getBoolean(old, def)))

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

    private fun saveInt(name: String, value: Int) { prefs().edit().putInt(pkey(name), value).apply() }
    private fun saveString(name: String, value: String) { prefs().edit().putString(pkey(name), value).apply() }
    private fun saveBool(name: String, value: Boolean) { prefs().edit().putBoolean(pkey(name), value).apply() }

    // ---------------------------------------------------------------- tối ưu màn hình

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
            ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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

    private fun pxToDp(px: Int): Int = (px / resources.displayMetrics.density).roundToInt()

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

    /** Tách đôi kiểu Gboard (màn hình trong): dãy số, G/V lặp, dấu cách liền 1 phím. */
    private fun splitLayout(): List<List<Key>> {
        val g = splitGapWeight()
        val spaceW = 5.0f + g
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
                    "ghjkl".map { t(it.toString()) } + // G lặp bên trái H
                    listOf(Key(Kind.SPACER, weight = 0.25f)),
                listOf(Key(Kind.SHIFT, "⇧", 1.25f)) +
                    "zxcv".map { t(it.toString()) } + gap(g) + // V lặp bên phải C
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
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

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
                key = Color.parseColor("#636366"), special = Color.parseColor("#48484A"),
                pressedKey = Color.parseColor("#8E8E93"), pressedSpecial = Color.parseColor("#636366"),
                text = Color.WHITE, specialText = Color.WHITE, accent = accent,
                popupBg = Color.parseColor("#636366"), popupText = Color.WHITE,
                panel = Color.parseColor("#2C2C2E"), keyStroke = 0,
            )
        } else {
            val baseBg = Color.parseColor("#D1D3D9")
            val bg = if (kc.lightTint != 0) blend(baseBg, kc.lightTint, 0.25f) else baseBg
            Colors(
                background = bg, key = Color.WHITE, special = Color.parseColor("#ADB3BC"),
                pressedKey = Color.parseColor("#BFC3C9"), pressedSpecial = Color.parseColor("#8E959D"),
                text = Color.BLACK, specialText = Color.BLACK, accent = accent,
                popupBg = Color.WHITE, popupText = Color.BLACK,
                panel = Color.parseColor("#E5E6EA"), keyStroke = 0,
            )
        }
    }

    private fun samsungColors(dark: Boolean, accent: Int): Colors {
        val kc = currentColor()
        return if (dark) {
            val baseBg = Color.parseColor("#010101")
            val bg = if (kc.darkTint != 0) blend(baseBg, kc.darkTint, 0.35f) else baseBg
            Colors(
                background = bg, key = Color.parseColor("#3A3A3C"), special = Color.parseColor("#2C2C2E"),
                pressedKey = Color.parseColor("#555557"), pressedSpecial = Color.parseColor("#444446"),
                text = Color.parseColor("#F5F5F5"), specialText = Color.parseColor("#F5F5F5"), accent = accent,
                popupBg = Color.parseColor("#4A4A4C"), popupText = Color.WHITE,
                panel = Color.parseColor("#1C1C1E"), keyStroke = 0,
            )
        } else {
            val baseBg = Color.parseColor("#F0F0F0")
            val bg = if (kc.lightTint != 0) blend(baseBg, kc.lightTint, 0.20f) else baseBg
            Colors(
                background = bg, key = Color.WHITE, special = Color.parseColor("#E1E3E6"),
                pressedKey = Color.parseColor("#D5D7DA"), pressedSpecial = Color.parseColor("#C8CBD0"),
                text = Color.parseColor("#191919"), specialText = Color.parseColor("#191919"), accent = accent,
                popupBg = Color.WHITE, popupText = Color.parseColor("#191919"),
                panel = Color.parseColor("#E6E8EB"), keyStroke = Color.parseColor("#E0E0E0"),
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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(rowH))
                    .apply { bottomMargin = dp(rowBottom) }
            }
            for (key in row) rowView.addView(makeKeyView(key, c, textScale))
            root.addView(rowView)
        }
        popup.setTextColor(c.popupText)
        popup.background = rounded(c.popupBg, if (isWideScreen()) 14 else 10)
    }

    private fun buildAdjustBar(c: Colors): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c.panel)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val wide = isWideScreen()
        val eff = effectiveLayout()
        val orientLabel = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "Ngang" else "Dọc"
        val screenLabel = if (wide) "Màn hình trong" else "Màn hình ngoài"

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        }
        if (wide) {
            row1.addView(chip("Bình thường", 1.1f, c, accent = eff == KbLayout.NORMAL) { setWideLayout("normal") })
            row1.addView(chip("Tách đôi", 1.0f, c, accent = eff == KbLayout.SPLIT) { setWideLayout("split") })
            row1.addView(chip("Nổi", 0.7f, c, accent = eff == KbLayout.FLOATING) { setWideLayout("floating") })
        } else {
            row1.addView(labelChip("$screenLabel - $orientLabel (riêng)", 2.8f, c))
        }
        row1.addView(chip("Đóng", 0.8f, c) { showKbSettings = false; rebuild() })
        bar.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        }
        row2.addView(labelChip("Cao", 0.5f, c))
        row2.addView(chip("−", 0.4f, c) { adjustHeight(-2) })
        row2.addView(labelChip("${effectiveKeyHeight()}", 0.4f, c))
        row2.addView(chip("+", 0.4f, c) { adjustHeight(2) })
        row2.addView(chip("Âm: ${soundModes[soundMode]}", 1.3f, c) { cycleSound() })
        row2.addView(chip(if (autoCorrect) "Sửa lỗi: Bật" else "Sửa lỗi: Tắt", 1.2f, c, accent = autoCorrect) { toggleAutoCorrect() })
        bar.addView(row2)

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
        }
        val styleLabel = if (kbStyle == "samsung") "Giao diện: Samsung" else "Giao diện: iPhone"
        row3.addView(chip(styleLabel, 1.3f, c) { cycleStyle() })
        row3.addView(chip("Màu: ${currentColor().name}", 1.1f, c) { cycleColor() })
        val tuneLabel = if (showDeepTune) "Tinh chỉnh ▲" else "Tinh chỉnh ▼"
        row3.addView(chip(tuneLabel, 0.9f, c, accent = showDeepTune) { showDeepTune = !showDeepTune; rebuild() })
        bar.addView(row3)

        if (showDeepTune) {
            val row4 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
            }
            row4.addView(labelChip("Lề", 0.45f, c)); row4.addView(chip("−", 0.4f, c) { adjustSideInset(-2) })
            row4.addView(labelChip("$sideInsetDp", 0.35f, c)); row4.addView(chip("+", 0.4f, c) { adjustSideInset(2) })
            row4.addView(labelChip("Ngang", 0.55f, c)); row4.addView(chip("−", 0.4f, c) { adjustHGap(-1) })
            row4.addView(labelChip("$keyHGapDp", 0.3f, c)); row4.addView(chip("+", 0.4f, c) { adjustHGap(1) })
            row4.addView(labelChip("Dọc", 0.45f, c)); row4.addView(chip("−", 0.4f, c) { adjustVGap(-1) })
            row4.addView(labelChip("$keyVGapDp", 0.3f, c)); row4.addView(chip("+", 0.4f, c) { adjustVGap(1) })
            bar.addView(row4)

            val row5 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30))
            }
            row5.addView(labelChip("Đang chỉnh: $screenLabel - $orientLabel (riêng). Lề: mép→giữa, Ngang/Dọc: khoảng cách phím", 4f, c))
            bar.addView(row5)
        }

        if (eff == KbLayout.FLOATING) {
            val drag = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
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
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
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

    private fun cycleStyle() { kbStyle = if (kbStyle == "iphone") "samsung" else "iphone"; saveString("style", kbStyle); rebuild() }
    private fun cycleColor() { kbColorIndex = (kbColorIndex + 1) % kbColors.size; saveInt("color", kbColorIndex); rebuild() }
    private fun cycleSound() { soundMode = (soundMode + 1) % soundModes.size; saveInt("sound", soundMode); rebuild() }
    private fun toggleAutoCorrect() { autoCorrect = !autoCorrect; saveBool("autocorrect", autoCorrect); rebuild() }
    private fun setWideLayout(mode: String) { wideLayoutPref = mode; saveString("layout", mode); rebuild() }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startMarginStart = 0
        private var startMarginBottom = 0
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    startMarginStart = floatMarginStartDp; startMarginBottom = floatMarginBottomDp
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dxDp = pxToDp((ev.rawX - startRawX).roundToInt())
                    val dyDp = pxToDp((ev.rawY - startRawY).roundToInt())
                    floatMarginStartDp = startMarginStart + dxDp
                    floatMarginBottomDp = startMarginBottom - dyDp
                    saveFloatState(); applyRootLayout(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun nudgeFloat(dx: Int, dy: Int) { floatMarginStartDp += dx; floatMarginBottomDp += dy; saveFloatState(); applyRootLayout() }
    private fun adjustFloatWidth(delta: Int) { floatWidthDp += delta; saveInt("fw", floatWidthDp); applyRootLayout() }
    private fun saveFloatState() {
        prefs().edit().putInt(pkey("fx"), floatMarginStartDp).putInt(pkey("fy"), floatMarginBottomDp).apply()
    }
    private fun adjustHeight(delta: Int) { keyHeightPref = (keyHeightPref + delta).coerceIn(36, 78); saveInt("height", keyHeightPref); rebuild() }
    private fun adjustSideInset(delta: Int) { sideInsetDp = (sideInsetDp + delta).coerceIn(0, 60); saveInt("inset", sideInsetDp); rebuild() }
    private fun adjustHGap(delta: Int) { keyHGapDp = (keyHGapDp + delta).coerceIn(0, 10); saveInt("hgap", keyHGapDp); rebuild() }
    private fun adjustVGap(delta: Int) { keyVGapDp = (keyVGapDp + delta).coerceIn(0, 10); saveInt("vgap", keyVGapDp); rebuild() }
    private fun adjustAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun displayLabel(key: Key): String =
        if (key.kind == Kind.TEXT && layer == Layer.LETTERS && shift != Shift.OFF) key.label.uppercase() else key.label

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
        if (key.kind == Kind.SPACER) return View(this).apply { layoutParams = params }

        val isEnter = key.kind == Kind.ENTER
        val isSpecial = key.kind != Kind.TEXT && key.kind != Kind.SPACE
        val baseColor = when { isEnter -> c.accent; isSpecial -> c.special; else -> c.key }
        val pressedColor = when { isEnter -> lightenPress(c.accent); isSpecial -> c.pressedSpecial; else -> c.pressedKey }
        val textColor = if (isEnter) Color.WHITE else c.text
        val radius = keyCornerDp()

        return TextView(this).apply {
            layoutParams = params
            text = keyLabel(key)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            typeface = if (key.kind == Kind.TEXT) Typeface.DEFAULT else Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, when (key.kind) {
                Kind.TEXT -> 22f * textScale; Kind.SPACE -> 15f * textScale; Kind.ENTER -> 15f * textScale; else -> 17f * textScale
            })
            background = rounded(baseColor, radius, c.keyStroke)
            elevation = if (key.kind == Kind.TEXT || key.kind == Kind.SPACE) dp(1).toFloat() else 0f
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
                if (key.kind == Kind.DELETE) { deleteOnce(); handler.postDelayed(repeatDelete, 400) }
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = ev.x >= 0 && ev.x <= v.width && ev.y >= 0 && ev.y <= v.height
                v.background = rounded(if (inside) pressedColor else baseColor, radius, if (inside) 0 else stroke)
                if (!inside) hidePopup()
            }
            MotionEvent.ACTION_UP -> {
                v.background = rounded(baseColor, radius, stroke)
                hidePopup()
                if (key.kind == Kind.DELETE) { handler.removeCallbacks(repeatDelete); refreshShift() }
                else {
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
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            ) || v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
        } else v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
        val wide = isWideScreen()
        val textSize = if (wide) 68f else 34f
        popup.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        popup.alpha = 0f; popup.scaleX = 0.6f; popup.scaleY = 0.6f; popup.translationY = dp(6).toFloat()
        popup.visibility = View.VISIBLE
        popup.bringToFront()

        val lp = popup.layoutParams as FrameLayout.LayoutParams
        val baseW = if (text.length <= 1) 58 else 74
        val baseH = 66
        lp.width = dp(if (wide) baseW * 2 else baseW)
        lp.height = dp(if (wide) baseH * 2 else baseH)
        val locA = IntArray(2); val locC = IntArray(2)
        anchor.getLocationInWindow(locA); container.getLocationInWindow(locC)
        val ax = locA[0] - locC[0] + anchor.width / 2 - lp.width / 2
        val ay = locA[1] - locC[1] - lp.height - dp(4)
        val cw = if (container.width > 0) container.width else resources.displayMetrics.widthPixels
        lp.leftMargin = ax.coerceIn(dp(2), (cw - lp.width - dp(2)).coerceAtLeast(dp(2)))
        lp.topMargin = ay.coerceAtLeast(dp(2))
        popup.layoutParams = lp
        popup.pivotX = lp.width / 2f; popup.pivotY = lp.height.toFloat()
        popup.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(120).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hidePopup() {
        if (!::popup.isInitialized) return
        if (popup.visibility != View.VISIBLE) { popup.animate().cancel(); return }
        popup.animate().cancel()
        popup.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f)
            .setDuration(90).setInterpolator(DecelerateInterpolator())
            .withEndAction { popup.visibility = View.GONE; popup.alpha = 1f; popup.scaleX = 1f; popup.scaleY = 1f }
            .start()
    }

    // ---------------------------------------------------------------- Telex / nhập liệu

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
    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private fun updateComposing() {
        val ic = currentInputConnection ?: return
        if (telex.isEmpty) ic.commitText("", 1) else ic.setComposingText(telex.text(), 1)
    }

    private fun finishTelex() {
        if (telex.isEmpty) return
        telex.reset()
        currentInputConnection?.finishComposingText()
    }

    // ---------------------------------------------------------------- xử lý phím

    private fun onKey(key: Key) {
        if (translateOpen) {
            handleTranslateModeKey(key)
            return
        }
        applyKeyToApp(key)
    }

    private fun applyKeyToApp(key: Key) {
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
        val before = ic.getTextBeforeCursor(50, 0)?.toString().orEmpty()
        if (now - lastSpaceTap < 400 && before.length >= 2 &&
            before.last() == ' ' && before[before.length - 2].isLetterOrDigit()
        ) {
            val word = extractLastWord(before.dropLast(1))
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTap = 0
            requestAutoCorrect(word, ". ")
        } else {
            val word = extractLastWord(before)
            ic.commitText(" ", 1)
            lastSpaceTap = now
            requestAutoCorrect(word, " ")
        }
        refreshShift()
    }

    private fun extractLastWord(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        var i = text.length - 1
        while (i >= 0) {
            val c = text[i]
            if (c.isLetter() || c == '\'' || c == '’' || c.code > 127) { sb.append(c); i-- } else break
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
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        refreshShift()
    }

    // ---------------------------------------------------------------- tự sửa chính tả

    private fun requestAutoCorrect(word: String, separator: String) {
        if (!autoCorrect || word.length < 2) return
        val lower = word.lowercase(Locale.getDefault())
        val fix = commonFixes[lower]
        if (fix != null) {
            val replacement = matchCase(word, fix)
            val ic = currentInputConnection ?: return
            handler.post {
                val before = currentInputConnection?.getTextBeforeCursor(word.length + separator.length + 2, 0)?.toString().orEmpty()
                if (before.endsWith(word + separator)) {
                    currentInputConnection?.deleteSurroundingText(word.length + separator.length, 0)
                    currentInputConnection?.commitText(replacement + separator, 1)
                }
            }
            return
        }
        acWord = word; acWordLen = word.length; acSeparator = separator
        val locale = if (vietnamese) Locale("vi", "VN") else Locale("en", "US")
        try {
            if (scSession == null || scLocale != locale) {
                try { scSession?.close() } catch (_: Exception) {}
                val tsm = getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
                scSession = tsm?.newSpellCheckerSession(null, locale, this, true)
                scLocale = locale
            }
            scSession?.getSuggestions(TextInfo(word), 5)
        } catch (_: Exception) {}
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
        var best: String? = null
        for (i in 0 until info.suggestionsCount) {
            val s = info.getSuggestionAt(i) ?: continue
            if (s.isNotBlank() && !s.equals(word, ignoreCase = true)) { best = s; break }
        }
        if (best == null) return
        val shouldApply = looksTypo || hasRecommended || !inDict
        if (!shouldApply) return
        handler.post {
            val ic = currentInputConnection ?: return@post
            val before = ic.getTextBeforeCursor(len + sep.length + 2, 0)?.toString().orEmpty()
            if (before.endsWith(word + sep)) {
                val replacement = matchCase(word, best)
                ic.deleteSurroundingText(len + sep.length, 0)
                ic.commitText(replacement + sep, 1)
            }
        }
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {}

    private fun matchCase(original: String, suggestion: String): String {
        if (original.isEmpty() || suggestion.isEmpty()) return suggestion
        val hasLetters = original.any { it.isLetter() }
        return when {
            hasLetters && original.all { it.isUpperCase() || !it.isLetter() } -> suggestion.uppercase(Locale.getDefault())
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
            // bấm lại: ngắt thu âm và xử lý ngay kết quả (dịch nếu VI→EN)
            if (voiceStopping) return
            voiceStopping = true
            handler.removeCallbacks(voiceStopFallbackRunnable)
            voiceStopFallbackText = lastVoicePartialVi
            if (voiceMode == 2) {
                showVoiceStatus("Đang dịch...", 0)
                if (voiceStopFallbackText.isNotBlank()) {
                    // dự phòng: một số máy không trả kết quả cuối khi stop sớm
                    handler.postDelayed(voiceStopFallbackRunnable, 1200)
                }
            } else {
                showVoiceStatus("Đang xử lý...", 0)
            }
            try { speechRecognizer?.stopListening() } catch (_: Exception) { stopVoice(resetStatus = false, cancel = true) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showVoiceStatus("Không hỗ trợ giọng nói", 2000); return
        }
        if (!hasRecordAudioPermission()) {
            showVoiceStatus("Cần cấp quyền Micro", 2500)
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
            if (speechRecognizer == null) speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            voiceComposing = false
            lastVoicePartialVi = ""
            voiceStopping = false
            voiceStopFallbackText = ""
            handler.removeCallbacks(voiceStopFallbackRunnable)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    showVoiceStatus(if (mode == 2) "Nghe (VI→EN)..." else "Đang nghe...", 0)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    showVoiceStatus(if (voiceMode == 2) "Đang dịch..." else "Đang xử lý...", 0)
                }
                override fun onError(error: Int) {
                    handler.removeCallbacks(voiceStopFallbackRunnable)
                    // ngắt thu âm sớm: một số máy trả NO_MATCH dù đã có kết quả tạm -> vẫn dịch
                    if (voiceStopping && voiceMode == 2 && lastVoicePartialVi.isNotBlank()) {
                        voiceStopping = false
                        voiceMode = 0
                        if (voiceComposing) {
                            try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                            voiceComposing = false
                        }
                        showVoiceStatus("Đang dịch...", 0)
                        translateVoiceFinal(lastVoicePartialVi)
                        return
                    }
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Không nhận dạng được"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi mạng"
                        SpeechRecognizer.ERROR_AUDIO -> "Lỗi âm thanh"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Cần quyền Micro"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không nghe rõ"
                        else -> "Lỗi giọng nói"
                    }
                    if (voiceComposing) {
                        try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                        voiceComposing = false
                    }
                    voiceMode = 0
                    voiceStopping = false
                    showVoiceStatus(msg, 2000)
                }
                override fun onResults(results: Bundle?) {
                    handler.removeCallbacks(voicePartialRunnable)
                    handler.removeCallbacks(voiceStopFallbackRunnable)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    var text = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    if (text.isBlank() && voiceStopping && voiceMode == 2) text = lastVoicePartialVi
                    val m = voiceMode
                    voiceMode = 0
                    voiceStopping = false
                    if (text.isBlank()) {
                        if (voiceComposing) try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                        voiceComposing = false
                        showVoiceStatus("Không nhận dạng được", 2000)
                        return
                    }
                    if (m == 2) {
                        showVoiceStatus("Đang dịch...", 0)
                        translateVoiceFinal(text)
                    } else {
                        commitVoiceText(text)
                        voiceStatus = null
                        rebuild()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    if (partial.isBlank()) return
                    if (voiceMode == 1) {
                        currentInputConnection?.setComposingText(partial, 1)
                        voiceComposing = true
                    } else if (voiceMode == 2) {
                        // tạm hiện tiếng Việt đang nói, đồng thời dịch nền để hiện EN sớm
                        if (partial != lastVoicePartialVi) {
                            lastVoicePartialVi = partial
                            if (!voiceComposing) {
                                currentInputConnection?.setComposingText(partial, 1)
                                voiceComposing = true
                            }
                            handler.removeCallbacks(voicePartialRunnable)
                            handler.postDelayed(voicePartialRunnable, 350)
                        }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            voiceMode = mode
            speechRecognizer?.startListening(intent)
            showVoiceStatus(if (mode == 2) "Nghe (VI→EN)..." else "Đang nghe...", 0)
        } catch (e: Exception) {
            voiceMode = 0
            voiceStopping = false
            showVoiceStatus("Không thể mở micro", 2000)
        }
    }

    private fun runVoicePartialTranslate() {
        val vi = lastVoicePartialVi
        if (vi.isBlank() || voiceMode != 2) return
        val job = ++voicePartialJob
        Thread {
            var out: String? = null
            try { out = requestTranslation(vi, "en") } catch (_: Exception) {}
            handler.post {
                if (job != voicePartialJob || voiceMode != 2) return@post
                if (!out.isNullOrEmpty()) {
                    currentInputConnection?.setComposingText(out, 1)
                    voiceComposing = true
                }
            }
        }.start()
    }

    private fun translateVoiceFinal(text: String) {
        val job = ++voicePartialJob
        Thread {
            var out: String? = null
            var err = "Lỗi dịch"
            try {
                out = requestTranslation(text, "en")
            } catch (e: UnknownHostException) { err = "Không có mạng" }
            catch (e: SocketTimeoutException) { err = "Hết thời gian, thử lại" }
            catch (e: IOException) {
                val msg = e.message.orEmpty()
                err = when {
                    msg.contains("HTTP 429") -> "Bị giới hạn, thử lại sau"
                    msg.startsWith("HTTP") -> "Lỗi máy chủ, thử lại"
                    else -> "Lỗi mạng, thử lại"
                }
            } catch (_: Exception) { err = "Lỗi dịch" }
            handler.post {
                if (job != voicePartialJob) return@post
                voiceStatus = null
                if (!out.isNullOrEmpty()) {
                    commitVoiceText(out)
                } else {
                    commitVoiceText(text) // lỗi dịch -> vẫn chèn tiếng Việt
                    showVoiceStatus(err, 2000)
                    return@post
                }
                rebuild()
            }
        }.start()
    }

    private fun commitVoiceText(text: String) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        if (voiceComposing) {
            // thay vùng đang hiện song song bằng kết quả cuối (tránh nhân đôi chữ)
            ic.setComposingText(text, 1)
            ic.finishComposingText()
        } else {
            ic.commitText(text, 1)
        }
        voiceComposing = false
    }

    private fun stopVoice(resetStatus: Boolean, cancel: Boolean) {
        handler.removeCallbacks(voicePartialRunnable)
        handler.removeCallbacks(voiceStopFallbackRunnable)
        try {
            if (cancel) speechRecognizer?.cancel() else speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        if (voiceComposing) {
            try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
        }
        voiceComposing = false
        voiceMode = 0
        voiceStopping = false
        if (resetStatus) { voiceStatus = null; rebuild() }
    }

    private fun showVoiceStatus(msg: String?, clearAfterMs: Long = 0) {
        voiceStatus = msg
        rebuild()
        if (clearAfterMs > 0 && msg != null) {
            val snapshot = msg
            handler.postDelayed({
                if (voiceStatus == snapshot && voiceMode == 0) { voiceStatus = null; rebuild() }
            }, clearAfterMs)
        }
    }

    // ---------------------------------------------------------------- tự viết hoa

    private fun capsMode(): Int {
        val ic = currentInputConnection ?: return 0
        val inputType = currentInputEditorInfo?.inputType ?: 0
        return ic.getCursorCapsMode(inputType)
    }

    private fun refreshShift(forceRebuild: Boolean = false) {
        val before = shift
        if (layer == Layer.LETTERS && shift == Shift.OFF && capsMode() != 0) shift = Shift.ON
        if (shift != before || forceRebuild) rebuild()
    }

    // ---------------------------------------------------------------- bảng dịch (gõ nguồn trong bảng, bản dịch hiện thẳng lên khung chat)

    private fun trSourceDisplay(): String = trSourceBase + trSourceTelex.text()

    private fun openTranslate() {
        finishTelex()
        val ic = currentInputConnection
        trAnchorBefore = ""
        trAnchorAfter = ""
        trAppliedLen = 0
        trSourceBase = ""
        trSourceTelex.reset()

        if (ic != null) {
            try {
                val req = ExtractedTextRequest().apply { hintMaxChars = 10000; hintMaxLines = 300 }
                val et = ic.getExtractedText(req, 0)
                if (et != null && et.text != null) {
                    val text = et.text.toString()
                    val selStart = et.selectionStart.coerceIn(0, text.length)
                    val selEnd = et.selectionEnd.coerceIn(0, text.length)
                    val a = minOf(selStart, selEnd)
                    val b = maxOf(selStart, selEnd)
                    if (a != b) {
                        trAnchorBefore = text.substring(0, a)
                        trAnchorAfter = text.substring(b)
                        trSourceBase = text.substring(a, b)
                        trAppliedLen = trSourceBase.length
                    } else {
                        trAnchorBefore = text.substring(0, a)
                        trAnchorAfter = text.substring(b)
                        trSourceBase = ""
                        trAppliedLen = 0
                    }
                } else {
                    val sel = ic.getSelectedText(0)?.toString().orEmpty()
                    val before = ic.getTextBeforeCursor(5000, 0)?.toString().orEmpty()
                    val after = ic.getTextAfterCursor(5000, 0)?.toString().orEmpty()
                    if (sel.isNotEmpty()) {
                        trAnchorBefore = before
                        trAnchorAfter = after
                        trSourceBase = sel
                        trAppliedLen = sel.length
                    } else {
                        trAnchorBefore = before
                        trAnchorAfter = after
                        trSourceBase = ""
                        trAppliedLen = 0
                    }
                }
            } catch (_: Exception) {
                val sel = ic.getSelectedText(0)?.toString().orEmpty()
                trAnchorBefore = ""
                trAnchorAfter = ""
                trSourceBase = sel
                trAppliedLen = sel.length
            }
        }

        trSourceTelex.reset()
        trTranslating = false
        trFinalizing = false
        translateStatus = null
        translateOpen = true
        layer = Layer.LETTERS
        rebuild()
        val src = trSourceDisplay()
        if (src.isNotBlank()) {
            translateNow(auto = false)
        }
    }

    private fun closeTranslate() {
        handler.removeCallbacks(trAutoTranslate)
        if (!trSourceTelex.isEmpty) {
            trSourceBase += trSourceTelex.text()
            trSourceTelex.reset()
        }
        translateJob++
        translateOpen = false
        trTranslating = false
        trFinalizing = false
        translateStatus = null
        rebuild()
    }

    private fun resetTranslateState() {
        handler.removeCallbacks(trAutoTranslate)
        translateOpen = false
        trTranslating = false
        trFinalizing = false
        translateStatus = null
        trSourceBase = ""
        trSourceTelex.reset()
        trAnchorBefore = ""
        trAnchorAfter = ""
        trAppliedLen = 0
        translateJob++
    }

    private fun handleTranslateModeKey(key: Key) {
        if (key.kind == Kind.ENTER) {
            if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }
            trFinalizing = true
            translateNow(auto = false)
            return
        }
        when (key.kind) {
            Kind.TEXT -> {
                val text = displayLabel(key)
                if (trTelexOn() && text.length == 1 && isAsciiLetter(text[0])) {
                    trSourceTelex.feed(text[0])
                } else {
                    if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }
                    trSourceBase += text
                }
                val before = shift
                if (shift == Shift.ON) shift = Shift.OFF
                if (before != shift) rebuild() else onTrSourceChanged()
            }
            Kind.SPACE -> {
                if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }
                trSourceBase += " "
                onTrSourceChanged()
            }
            Kind.DELETE -> {
                if (!trSourceTelex.isEmpty) {
                    trSourceTelex.backspace()
                } else if (trSourceBase.isNotEmpty()) {
                    trSourceBase = trSourceBase.dropLast(1)
                }
                onTrSourceChanged()
            }
            Kind.SHIFT -> {
                onShiftTap() // rebuilds
            }
            Kind.LANG -> {
                if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }
                vietnamese = !vietnamese
                prefs().edit().putBoolean("vietnamese", vietnamese).apply()
                rebuild()
            }
            Kind.TO_SYMBOLS_1 -> { if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }; layer = Layer.SYMBOLS_1; rebuild() }
            Kind.TO_SYMBOLS_2 -> { if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }; layer = Layer.SYMBOLS_2; rebuild() }
            Kind.TO_LETTERS -> {
                if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }
                layer = Layer.LETTERS
                if (shift == Shift.OFF && capsMode() != 0) shift = Shift.ON
                rebuild()
            }
            else -> Unit
        }
    }

    private fun trTelexOn(): Boolean = vietnamese && layer == Layer.LETTERS

    private fun onTrSourceChanged() {
        translateStatus = null
        scheduleTrAutoTranslate()
        rebuild()
    }

    private fun scheduleTrAutoTranslate() {
        handler.removeCallbacks(trAutoTranslate)
        if (translateOpen && trSourceDisplay().isNotBlank()) {
            handler.postDelayed(trAutoTranslate, 600)
        } else if (translateOpen) {
            translateJob++ // hủy kết quả đang chạy
            trTranslating = false
            trFinalizing = false
            translateStatus = null
            applyTranslationToChat("") // xóa bản dịch nếu nguồn trống
        }
    }

    private fun buildToolbar(c: Colors): View {
        if (!translateOpen) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
            }
            bar.addView(chip("Dịch", 1.0f, c) { openTranslate() })
            val micViLabel = if (voiceMode == 1) "⏹" else "🎤"
            val micEnLabel = if (voiceMode == 2) "⏹" else "VI→EN"
            bar.addView(chip(micViLabel, 0.7f, c, accent = voiceMode == 1) { startVoice(1) })
            bar.addView(chip(micEnLabel, 0.9f, c, accent = voiceMode == 2) { startVoice(2) })
            if (voiceStatus != null) {
                bar.addView(labelChip(voiceStatus!!, 1.7f, c))
            } else {
                bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.7f) })
            }
            bar.addView(chip("⌨", 0.7f, c) { showKbSettings = !showKbSettings; rebuild() })
            return bar
        }
        return buildTranslatePanel(c)
    }

    private fun buildTranslatePanel(c: Colors): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val control = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        }
        control.addView(chip("Sang: " + targets[targetIndex].name, 2.3f, c) {
            targetIndex = (targetIndex + 1) % targets.size
            rebuild()
            if (trSourceDisplay().isNotBlank()) {
                trFinalizing = true
                translateNow(auto = false)
            }
        })
        val actionLabel = when {
            trTranslating -> "Đang dịch..."
            translateStatus != null -> translateStatus!!
            else -> "Dịch"
        }
        control.addView(chip(actionLabel, 1.1f, c, accent = true) {
            if (!trSourceTelex.isEmpty) { trSourceBase += trSourceTelex.text(); trSourceTelex.reset() }
            trFinalizing = true
            translateNow(auto = false)
        })
        control.addView(chip("⌨", 0.6f, c) { showKbSettings = !showKbSettings; rebuild() })
        control.addView(chip("✕", 0.6f, c) { closeTranslate() })
        panel.addView(control)

        val srcText = trSourceDisplay()
        val srcView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(3), dp(2), dp(3), dp(4)) }
            text = if (srcText.isEmpty()) "Gõ văn bản cần dịch..." else srcText
            setTextColor(if (srcText.isEmpty()) Color.GRAY else c.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(c.key, panelCornerDp())
            minHeight = dp(44)
            maxLines = 4
        }
        panel.addView(srcView)

        if (translateStatus != null && trTranslating) {
            val st = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22))
                text = "Bản dịch đang hiện trực tiếp trên khung chat..."
                setTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), dp(2))
            }
            panel.addView(st)
        }
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
        setOnClickListener { performKeyHaptic(it); onClick() }
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
                if (translateStatus == snapshot) { translateStatus = null; rebuild() }
            }, clearAfterMs)
        }
    }

    private fun translateNow(auto: Boolean = false) {
        handler.removeCallbacks(trAutoTranslate)
        val source = trSourceDisplay()
        if (source.isBlank()) {
            if (!auto) setTrStatus("Chưa có chữ", 1500)
            applyTranslationToChat("")
            return
        }
        if (source.length > 4500) { setTrStatus("Quá dài", 2500); trFinalizing = false; return }

        val job = ++translateJob
        val target = targets[targetIndex].code
        trTranslating = true
        if (!auto) translateStatus = "Đang dịch..."
        rebuild()

        Thread {
            var out: String? = null
            var err = "Lỗi dịch"
            try {
                out = requestTranslation(source, target)
            } catch (e: UnknownHostException) { err = "Không có mạng" }
            catch (e: SocketTimeoutException) { err = "Hết thời gian, thử lại" }
            catch (e: IOException) {
                val msg = e.message.orEmpty()
                err = when {
                    msg.contains("HTTP 429") -> "Bị giới hạn, thử lại sau"
                    msg.startsWith("HTTP") -> "Lỗi máy chủ, thử lại"
                    else -> "Lỗi mạng, thử lại"
                }
            } catch (_: Exception) { err = "Lỗi dịch" }
            handler.post { onTrTranslated(job, out, err) }
        }.start()
    }

    private fun onTrTranslated(job: Int, out: String?, err: String) {
        if (job != translateJob) return
        trTranslating = false
        if (out.isNullOrEmpty()) {
            translateStatus = err
            trFinalizing = false
            rebuild()
        } else {
            translateStatus = null
            applyTranslationToChat(out)
            if (trFinalizing) {
                trFinalizing = false
                closeTranslate()
            } else {
                rebuild()
            }
        }
    }

    /** Thay thế vùng đang dịch trong khung chat bằng bản dịch (hiện thẳng). */
    private fun applyTranslationToChat(translated: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            // chọn vùng [trAnchorBefore.length, trAnchorBefore.length + trAppliedLen] và thay thế
            val start = trAnchorBefore.length
            val end = start + trAppliedLen
            val ok = try { ic.setSelection(start, end); true } catch (_: Exception) { false }
            if (ok) {
                ic.commitText(translated, 1)
            } else {
                // fallback: đặt lại toàn bộ
                try {
                    val req = ExtractedTextRequest().apply { hintMaxChars = 100000 }
                    val et = ic.getExtractedText(req, 0)
                    val fullLen = et?.text?.length ?: (trAnchorBefore.length + trAppliedLen + trAnchorAfter.length)
                    ic.setSelection(0, fullLen)
                    ic.commitText(trAnchorBefore + translated + trAnchorAfter, 1)
                    ic.setSelection(trAnchorBefore.length + translated.length, trAnchorBefore.length + translated.length)
                } catch (_: Exception) {
                    ic.commitText(translated, 1)
                }
            }
            trAppliedLen = translated.length
        } finally {
            ic.endBatchEdit()
        }
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
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write("$params&q=$encodedText".toByteArray(Charsets.UTF_8)) }
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
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
