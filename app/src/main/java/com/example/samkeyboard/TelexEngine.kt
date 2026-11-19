package com.example.samkeyboard

/**
 * Bộ gõ tiếng Việt kiểu Telex. Giữ trạng thái của 1 từ đang gõ.
 *
 * Quy tắc:
 *  - Dấu thanh: s=sắc, f=huyền, r=hỏi, x=ngã, j=nặng, z=xóa dấu
 *  - aa=â, ee=ê, oo=ô, aw=ă, ow=ơ, uw=ư, dd=đ, uow=ươ
 *  - Gõ lặp lại phím vừa tạo dấu thì hủy dấu (aaa -> aa, ss -> s, ww -> w)
 */
class TelexEngine {

    // mod: 0 không, 1 mũ (â ê ô), 2 trăng (ă), 3 móc (ơ ư), 4 gạch (đ)
    private class Letter(var base: Char, var upper: Boolean, var mod: Int, var synth: Boolean = false)

    private val letters = ArrayList<Letter>()
    private var tone = 0 // 0 không dấu, 1 sắc, 2 huyền, 3 hỏi, 4 ngã, 5 nặng

    val isEmpty: Boolean get() = letters.isEmpty()

    fun reset() {
        letters.clear()
        tone = 0
    }

    fun backspace() {
        if (letters.isEmpty()) return
        letters.removeAt(letters.size - 1)
        if (vowelFlags().none { it }) tone = 0
    }

    /** Nhận 1 chữ cái a-z hoặc A-Z. */
    fun feed(ch: Char) {
        val k = ch.lowercaseChar()
        val up = ch.isUpperCase()
        if (tryTone(k)) return
        if (tryMark(k, up)) return
        letters.add(Letter(k, up, 0))
    }

    /** Chuỗi đang hiển thị của từ hiện tại. */
    fun text(): String {
        if (letters.isEmpty()) return ""
        val f = vowelFlags()
        val tp = if (tone != 0) tonePosition(f) else -1
        val sb = StringBuilder()
        for (i in letters.indices) {
            val l = letters[i]
            var c = glyph(l, if (i == tp) tone else 0)
            if (l.upper) c = c.uppercaseChar()
            sb.append(c)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ nguyên âm

    private fun forms(base: Char, mod: Int): String? = when (base) {
        'a' -> when (mod) {
            0 -> "aáàảãạ"
            1 -> "âấầẩẫậ"
            2 -> "ăắằẳẵặ"
            else -> null
        }
        'e' -> when (mod) {
            0 -> "eéèẻẽẹ"
            1 -> "êếềểễệ"
            else -> null
        }
        'i' -> if (mod == 0) "iíìỉĩị" else null
        'o' -> when (mod) {
            0 -> "oóòỏõọ"
            1 -> "ôốồổỗộ"
            3 -> "ơớờởỡợ"
            else -> null
        }
        'u' -> when (mod) {
            0 -> "uúùủũụ"
            3 -> "ưứừửữự"
            else -> null
        }
        'y' -> if (mod == 0) "yýỳỷỹỵ" else null
        else -> null
    }

    private fun glyph(l: Letter, t: Int): Char {
        if (l.base == 'd' && l.mod == 4) return 'đ'
        val s = forms(l.base, l.mod) ?: return l.base
        return s[t]
    }

    /** true nếu chữ ở vị trí đó đang đóng vai nguyên âm (u sau q và i trong "gi" là phụ âm). */
    private fun vowelFlags(): BooleanArray {
        val n = letters.size
        val f = BooleanArray(n) { "aeiouy".indexOf(letters[it].base) >= 0 }
        for (i in 1 until n) {
            if (letters[i].base == 'u' && letters[i - 1].base == 'q') f[i] = false
            if (letters[i].base == 'i' && letters[i - 1].base == 'g' && i + 1 < n && f[i + 1]) f[i] = false
        }
        return f
    }

    private fun lastVowelRun(f: BooleanArray): IntArray? {
        var end = -1
        for (i in letters.indices.reversed()) {
            if (f[i]) {
                end = i
                break
            }
        }
        if (end < 0) return null
        var start = end
        while (start > 0 && f[start - 1]) start--
        return intArrayOf(start, end)
    }

    private fun tonePosition(f: BooleanArray): Int {
        val run = lastVowelRun(f) ?: return -1
        val start = run[0]
        val end = run[1]
        // nguyên âm có dấu mũ/trăng/móc được ưu tiên (ươ -> ơ, iê -> ê)
        var marked = -1
        for (i in start..end) if (letters[i].mod in 1..3) marked = i
        if (marked >= 0) return marked
        val len = end - start + 1
        if (len == 1) return start
        if (len == 2) {
            val pair = "" + letters[start].base + letters[start + 1].base
            // oa, oe, uy: có phụ âm cuối thì dấu ở chữ sau (toán, hoàn, huỳnh),
            // không có thì dấu ở chữ đầu (hòa, khỏe, thủy)
            val closed = end < letters.size - 1
            return if ((pair == "oa" || pair == "oe" || pair == "uy") && closed) start + 1 else start
        }
        return start + 1
    }

    // ------------------------------------------------------------ dấu thanh

    private fun tryTone(k: Char): Boolean {
        val t = when (k) {
            's' -> 1
            'f' -> 2
            'r' -> 3
            'x' -> 4
            'j' -> 5
            'z' -> 0
            else -> return false
        }
        if (vowelFlags().none { it }) return false
        if (k == 'z') {
            if (tone == 0) return false
            tone = 0
            return true
        }
        if (tone == t) {
            tone = 0 // bấm lại lần 2: bỏ dấu và gõ chữ thường
            return false
        }
        tone = t
        return true
    }

    // ------------------------------------------------------------ dấu mũ, móc, trăng, gạch

    private fun tryMark(k: Char, up: Boolean): Boolean {
        if (k == 'd') {
            if (letters.isEmpty()) return false
            val last = letters[letters.size - 1]
            if (last.base != 'd') return false
            if (last.mod == 0) {
                last.mod = 4
                return true
            }
            if (last.mod == 4) {
                last.mod = 0
                letters.add(Letter('d', up, 0))
                return true
            }
            return false
        }
        if (k == 'a' || k == 'e' || k == 'o') return doubleVowel(k, up)
        if (k == 'w') return applyW(up)
        return false
    }

    private fun doubleVowel(k: Char, up: Boolean): Boolean {
        val f = vowelFlags()
        var i = letters.size - 1
        while (i >= 0 && f[i]) {
            val l = letters[i]
            if (l.base == k) {
                if (l.mod == 0) {
                    l.mod = 1
                    return true
                }
                if (l.mod == 1) {
                    l.mod = 0
                    letters.add(Letter(k, up, 0))
                    return true
                }
                return false
            }
            i--
        }
        return false
    }

    private fun applyW(up: Boolean): Boolean {
        // "ww" -> "w": hủy chữ ư vừa sinh ra từ phím w
        if (letters.isNotEmpty()) {
            val last = letters[letters.size - 1]
            if (last.synth && last.base == 'u' && last.mod == 3) {
                last.base = 'w'
                last.mod = 0
                last.synth = false
                return true
            }
        }
        val f = vowelFlags()
        val run = lastVowelRun(f)
        if (run == null) {
            // chưa có nguyên âm: w -> ư (ví dụ "thw" -> "thư")
            letters.add(Letter('u', up, 3, true))
            return true
        }
        val start = run[0]
        val end = run[1]

        // ươ
        for (i in start until end) {
            val a = letters[i]
            val b = letters[i + 1]
            if (a.base == 'u' && b.base == 'o') {
                if (a.mod == 3 && b.mod == 3) {
                    a.mod = 0
                    b.mod = 0
                    letters.add(Letter('w', up, 0))
                    return true
                }
                if ((a.mod == 0 || a.mod == 3) && (b.mod == 0 || b.mod == 3)) {
                    a.mod = 3
                    b.mod = 3
                    return true
                }
            }
        }

        var idx = -1
        for (i in end downTo start) {
            if (letters[i].base == 'a' || letters[i].base == 'o' || letters[i].base == 'u') {
                idx = i
                break
            }
        }
        if (idx < 0) return false
        var target = idx
        // "ua" + w -> "ưa" (không có "uă")
        if (letters[idx].base == 'a' && letters[idx].mod == 0 && idx - 1 >= start &&
            letters[idx - 1].base == 'u' && letters[idx - 1].mod == 0
        ) {
            target = idx - 1
        }
        val t = letters[target]
        val want = if (t.base == 'a') 2 else 3
        if (t.mod == 0) {
            t.mod = want
            return true
        }
        if (t.mod == want) {
            t.mod = 0
            letters.add(Letter('w', up, 0))
            return true
        }
        return false
    }
}
