import com.jethuit.spw.listenstats.util.DurationPart
import com.jethuit.spw.listenstats.util.durationParts
import com.jethuit.spw.listenstats.util.formatDuration
import com.jethuit.spw.listenstats.util.formatDurationCompact
import com.jethuit.spw.listenstats.util.formatDurationSeconds
import com.jethuit.spw.listenstats.util.DurationTableParts
import com.jethuit.spw.listenstats.util.durationTableParts

fun main(args: Array<String>) {
    val cases = mapOf(
        -1L to "0 分钟",
        0L to "0 分钟",
        29_999L to "0分钟",
        30_000L to "1分钟",
        59_999L to "1分钟",
        60_000L to "1分钟",
        89_999L to "1分钟",
        90_000L to "2分钟",
        119_999L to "2分钟",
        120_000L to "2分钟",
        149_999L to "2分钟",
        150_000L to "3分钟",
        3_569_999L to "59分钟",
        3_570_000L to "1小时",
        3_600_000L to "1小时",
        3_690_000L to "1小时2分钟",
        86_370_000L to "24小时"
    )
    cases.forEach { (millis, expected) ->
        check(formatDuration(millis) == expected) { "$millis: ${formatDuration(millis)} != $expected" }
    }
    check(formatDurationCompact(3_570_000) == ("1" to "小时"))
    check(durationParts(3_570_000) == listOf(DurationPart("1", "小时")))
    check(durationParts(90_000) == listOf(DurationPart("2", "分钟")))
    check(durationParts(3_690_000) == listOf(DurationPart("1", "小时"), DurationPart("2", "分")))
    // Aggregate raw time first: two 29-second spans display as one minute, without rounding each span.
    check(formatDuration(29_000 + 29_000L) == "1分钟")
    check(formatDuration(4 * 30_000L) == "2分钟")
    // Quotient + remainder must not overflow at Long.MAX_VALUE.
    check(formatDuration(Long.MAX_VALUE).isNotEmpty())
    check(durationParts(Long.MAX_VALUE).first().unit == "小时")
    println("PASS minute half-up boundaries, hour/day carry, shared displays and aggregate-before-rounding")
    mapOf(-1L to "0秒", 0L to "0秒", 1L to "不足1秒", 999L to "不足1秒",
        1_000L to "1秒", 59_999L to "59秒", 60_000L to "1分0秒",
        207_000L to "3分27秒", 3_599_999L to "59分59秒", 3_600_000L to "1小时0分0秒",
        3_728_999L to "1小时2分8秒", 86_400_000L to "24小时0分0秒").forEach { (millis, expected) ->
        check(formatDurationSeconds(millis) == expected) { "seconds $millis: ${formatDurationSeconds(millis)} != $expected" }
    }
    check(formatDurationSeconds(Long.MAX_VALUE).isNotEmpty())
    check(formatDurationSeconds(600L + 600L) == "1秒")
    println("PASS detail seconds, subsecond records, minute/hour boundaries and aggregate-before-formatting")
    mapOf(-1L to "0m", 0L to "0m", 29_999L to "0m", 30_000L to "1m",
        3_569_999L to "59m", 3_570_000L to "1h", 3_690_000L to "1h2m",
        86_400_000L to "24h").forEach { (millis, expected) ->
        check(formatDuration(millis, true) == expected)
    }
    mapOf(-1L to "0s", 0L to "0s", 1L to "<1s", 999L to "<1s", 1_000L to "1s",
        59_999L to "59s", 60_000L to "1m0s", 3_599_999L to "59m59s",
        3_600_000L to "1h0m0s", 3_728_999L to "1h2m8s",
        86_400_000L to "24h0m0s").forEach { (millis, expected) ->
        check(formatDurationSeconds(millis, true) == expected)
    }
    check(durationParts(3_690_000L, true) == listOf(DurationPart("1", "h"), DurationPart("2", "m")))
    check(durationParts(90_000L, true) == listOf(DurationPart("2", "m")))
    check(formatDurationCompact(3_570_000L, true) == ("1" to "h"))
    check(formatDuration(Long.MAX_VALUE, true).isNotEmpty() && formatDurationSeconds(Long.MAX_VALUE, true).isNotEmpty())
    // Unit choices must not turn overview rounding into detail truncation, or round individual spans.
    check(formatDuration(29_000L + 29_000L, true) == "1m")
    check(formatDurationSeconds(600L + 600L, true) == "1s")
    println("PASS Latin units preserve independent minute/second precision, boundaries and aggregate-before-formatting")
    mapOf(
        -1L to DurationTableParts("", "00m", "00s"),
        0L to DurationTableParts("", "00m", "00s"),
        999L to DurationTableParts("", "", "<1s"),
        1_000L to DurationTableParts("", "00m", "01s"),
        2_881_999L to DurationTableParts("", "48m", "01s"),
        3_600_000L to DurationTableParts("1h", "00m", "00s"),
        3_945_000L to DurationTableParts("1h", "05m", "45s"),
        450_009_000L to DurationTableParts("125h", "00m", "09s")
    ).forEach { (millis, expected) -> check(durationTableParts(millis, true) == expected) }
    check(durationTableParts(Long.MAX_VALUE, true).hours.endsWith("h"))
    mapOf(
        -1L to DurationTableParts("", "00分", "00秒"),
        0L to DurationTableParts("", "00分", "00秒"),
        999L to DurationTableParts("", "", "不足1秒"),
        1_000L to DurationTableParts("", "00分", "01秒"),
        2_881_999L to DurationTableParts("", "48分", "01秒"),
        3_600_000L to DurationTableParts("1小时", "00分", "00秒"),
        3_945_000L to DurationTableParts("1小时", "05分", "45秒"),
        450_009_000L to DurationTableParts("125小时", "00分", "09秒")
    ).forEach { (millis, expected) -> check(durationTableParts(millis, false) == expected) }
    check(durationTableParts(Long.MAX_VALUE, false).hours.endsWith("小时"))
    check(durationTableParts(1_000L, false, true) == DurationTableParts("", "", "01秒"))
    check(durationTableParts(3_601_000L, false, true) == DurationTableParts("1小时", "", "01秒"))
    check(durationTableParts(1_000L, true, true) == DurationTableParts("", "", "01s"))
    check(durationTableParts(3_601_000L, true, true) == DurationTableParts("1h", "", "01s"))
    check(durationTableParts(0L, false, true) == DurationTableParts("", "", "00秒"))
    check(durationTableParts(999L, false, true) == DurationTableParts("", "", "不足1秒"))
    check(durationTableParts(61_000L, false, true) == DurationTableParts("", "01分", "01秒"))
    check(formatDurationSeconds(3_601_000L, false, true) == "1小时1秒")
    check(formatDurationSeconds(3_601_000L, true, true) == "1h1s")
    println("PASS details omit zero minutes in both languages while ranking defaults remain unchanged")
    println("PASS table-only hour blanks, padded fields, completed seconds, zero/subseconds and long hours")
}
