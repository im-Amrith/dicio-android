package org.stypox.dicio.io.speech

/**
 * Prepares text for Kokoro, whose pronunciation dictionary only knows words: writes numbers,
 * times, percentages and a few symbols out in words ("6:30 pm" -> "six thirty p m").
 */
object SpeechText {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen",
    )
    private val TENS = listOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    private val TIME = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val PERCENT = Regex("""(\d+(?:\.\d+)?)\s?%""")
    private val DECIMAL = Regex("""\b(\d+)\.(\d+)\b""")
    private val NUMBER = Regex("""\b\d{1,3}(?:,\d{2,3})+\b|\b\d+\b""")
    private val CURRENCY = Regex("""[₹$]\s?(\d[\d,]*(?:\.\d+)?)""")

    fun normalize(text: String): String {
        var s = text
            .replace("&", " and ")
            .replace("°C", " degrees Celsius")
            .replace("°F", " degrees Fahrenheit")
            .replace("°", " degrees")
            .replace(Regex("""\b(a\.m\.|am)\b""", RegexOption.IGNORE_CASE), "A M")
            .replace(Regex("""\b(p\.m\.|pm)\b""", RegexOption.IGNORE_CASE), "P M")
        s = CURRENCY.replace(s) { m ->
            val unit = if (m.value.startsWith("₹")) "rupees" else "dollars"
            m.groupValues[1] + " " + unit
        }
        s = TIME.replace(s) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            when {
                min == 0 -> words(h.toLong()) + " o'clock"
                min < 10 -> words(h.toLong()) + " oh " + words(min.toLong())
                else -> words(h.toLong()) + " " + words(min.toLong())
            }
        }
        s = PERCENT.replace(s) { m -> m.groupValues[1] + " percent" }
        s = DECIMAL.replace(s) { m ->
            words(m.groupValues[1].toLong()) + " point " +
                m.groupValues[2].map { ONES[it - '0'] }.joinToString(" ")
        }
        s = NUMBER.replace(s) { m ->
            m.value.replace(",", "").toLongOrNull()?.let { words(it) } ?: m.value
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** 384000 -> "three hundred eighty four thousand"; Indian lakh/crore for big round numbers */
    fun words(n: Long): String {
        if (n < 0) return "minus " + words(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
        if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" +
            if (n % 100 != 0L) " " + words(n % 100) else ""
        for ((size, name) in listOf(
            1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand"
        )) {
            if (n >= size) {
                return words(n / size) + " " + name + if (n % size != 0L) " " + words(n % size) else ""
            }
        }
        return n.toString()
    }
}
