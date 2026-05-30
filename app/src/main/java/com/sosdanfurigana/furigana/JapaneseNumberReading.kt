package com.sosdanfurigana.furigana

object JapaneseNumberReading {
    fun readNumericExpression(surface: String): String? {
        if (surface.none { isDigitLike(it) }) return null
        val text = normalizeDigits(surface)
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (!text[index].isDigit()) return null
            val digitStart = index
            while (index < text.length && text[index].isDigit()) {
                index++
            }
            val value = text.substring(digitStart, index).toIntOrNull() ?: return null
            if (index >= text.length) {
                builder.append(numberReading(value) ?: return null)
                continue
            }
            val unit = text[index]
            index++
            builder.append(readWithUnit(value, unit) ?: return null)
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    private fun readWithUnit(value: Int, unit: Char): String? {
        return when (unit) {
            '年' -> "${numberReading(value) ?: return null}ねん"
            '月' -> "${monthNumberReading(value) ?: return null}がつ"
            '日' -> dateDayReading(value)
            '時' -> "${hourNumberReading(value) ?: return null}じ"
            '分' -> minuteReading(value)
            '秒' -> "${numberReading(value) ?: return null}びょう"
            '円' -> "${numberReading(value) ?: return null}えん"
            '歳', '才' -> ageReading(value)
            '人' -> personCountReading(value)
            '回' -> "${numberReading(value) ?: return null}かい"
            '個' -> "${numberReading(value) ?: return null}こ"
            '枚' -> "${numberReading(value) ?: return null}まい"
            '本' -> "${numberReading(value) ?: return null}ほん"
            '話' -> "${numberReading(value) ?: return null}わ"
            '巻' -> "${numberReading(value) ?: return null}かん"
            '号' -> "${numberReading(value) ?: return null}ごう"
            '度' -> "${numberReading(value) ?: return null}ど"
            else -> null
        }
    }

    private fun monthNumberReading(value: Int): String? {
        return when (value) {
            1 -> "いち"
            2 -> "に"
            3 -> "さん"
            4 -> "し"
            5 -> "ご"
            6 -> "ろく"
            7 -> "しち"
            8 -> "はち"
            9 -> "く"
            10 -> "じゅう"
            11 -> "じゅういち"
            12 -> "じゅうに"
            else -> null
        }
    }

    private fun hourNumberReading(value: Int): String? {
        return when (value) {
            0 -> "れい"
            1 -> "いち"
            2 -> "に"
            3 -> "さん"
            4 -> "よ"
            5 -> "ご"
            6 -> "ろく"
            7 -> "しち"
            8 -> "はち"
            9 -> "く"
            10 -> "じゅう"
            11 -> "じゅういち"
            12 -> "じゅうに"
            else -> numberReading(value)
        }
    }

    private fun dateDayReading(value: Int): String? {
        return when (value) {
            1 -> "ついたち"
            2 -> "ふつか"
            3 -> "みっか"
            4 -> "よっか"
            5 -> "いつか"
            6 -> "むいか"
            7 -> "なのか"
            8 -> "ようか"
            9 -> "ここのか"
            10 -> "とおか"
            11 -> "じゅういちにち"
            12 -> "じゅうににち"
            13 -> "じゅうさんにち"
            14 -> "じゅうよっか"
            15 -> "じゅうごにち"
            16 -> "じゅうろくにち"
            17 -> "じゅうしちにち"
            18 -> "じゅうはちにち"
            19 -> "じゅうくにち"
            20 -> "はつか"
            21 -> "にじゅういちにち"
            22 -> "にじゅうににち"
            23 -> "にじゅうさんにち"
            24 -> "にじゅうよっか"
            25 -> "にじゅうごにち"
            26 -> "にじゅうろくにち"
            27 -> "にじゅうしちにち"
            28 -> "にじゅうはちにち"
            29 -> "にじゅうくにち"
            30 -> "さんじゅうにち"
            31 -> "さんじゅういちにち"
            else -> null
        }
    }

    private fun minuteReading(value: Int): String? {
        if (value !in 0..59) return null
        if (value == 0) return "れいふん"
        if (value < 10) return minuteUnderTen(value)
        val tens = value / 10
        val ones = value % 10
        return if (ones == 0) {
            when (tens) {
                1 -> "じゅっぷん"
                2 -> "にじゅっぷん"
                3 -> "さんじゅっぷん"
                4 -> "よんじゅっぷん"
                5 -> "ごじゅっぷん"
                else -> null
            }
        } else {
            "${tenReading(tens)}${minuteUnderTen(ones) ?: return null}"
        }
    }

    private fun minuteUnderTen(value: Int): String? {
        return when (value) {
            1 -> "いっぷん"
            2 -> "にふん"
            3 -> "さんぷん"
            4 -> "よんぷん"
            5 -> "ごふん"
            6 -> "ろっぷん"
            7 -> "ななふん"
            8 -> "はっぷん"
            9 -> "きゅうふん"
            else -> null
        }
    }

    private fun ageReading(value: Int): String? {
        if (value == 20) return "はたち"
        val base = numberReading(value) ?: return null
        return when {
            value % 10 == 1 || value % 10 == 8 || value % 10 == 0 -> "${base}さい"
            else -> "${base}さい"
        }
    }

    private fun personCountReading(value: Int): String? {
        return when (value) {
            1 -> "ひとり"
            2 -> "ふたり"
            else -> "${numberReading(value) ?: return null}にん"
        }
    }

    private fun numberReading(value: Int): String? {
        if (value < 0 || value > 9999) return null
        if (value == 0) return "ぜろ"
        val thousands = value / 1000
        val hundreds = (value / 100) % 10
        val tens = (value / 10) % 10
        val ones = value % 10
        return buildString {
            append(thousandReading(thousands))
            append(hundredReading(hundreds))
            append(tenReading(tens))
            append(oneReading(ones))
        }.takeIf { it.isNotBlank() }
    }

    private fun thousandReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "せん"
            2 -> "にせん"
            3 -> "さんぜん"
            4 -> "よんせん"
            5 -> "ごせん"
            6 -> "ろくせん"
            7 -> "ななせん"
            8 -> "はっせん"
            9 -> "きゅうせん"
            else -> ""
        }
    }

    private fun hundredReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "ひゃく"
            2 -> "にひゃく"
            3 -> "さんびゃく"
            4 -> "よんひゃく"
            5 -> "ごひゃく"
            6 -> "ろっぴゃく"
            7 -> "ななひゃく"
            8 -> "はっぴゃく"
            9 -> "きゅうひゃく"
            else -> ""
        }
    }

    private fun tenReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "じゅう"
            2 -> "にじゅう"
            3 -> "さんじゅう"
            4 -> "よんじゅう"
            5 -> "ごじゅう"
            6 -> "ろくじゅう"
            7 -> "ななじゅう"
            8 -> "はちじゅう"
            9 -> "きゅうじゅう"
            else -> ""
        }
    }

    private fun oneReading(value: Int): String {
        return when (value) {
            0 -> ""
            1 -> "いち"
            2 -> "に"
            3 -> "さん"
            4 -> "よん"
            5 -> "ご"
            6 -> "ろく"
            7 -> "なな"
            8 -> "はち"
            9 -> "きゅう"
            else -> ""
        }
    }

    private fun normalizeDigits(value: String): String {
        return buildString {
            value.forEach { char ->
                append(
                    when (char) {
                        in '0'..'9' -> char
                        in '０'..'９' -> ('0'.code + (char.code - '０'.code)).toChar()
                        else -> char
                    }
                )
            }
        }
    }

    private fun isDigitLike(char: Char): Boolean {
        return char in '0'..'9' || char in '０'..'９'
    }
}
