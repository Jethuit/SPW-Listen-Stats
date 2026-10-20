import com.jethuit.spw.listenstats.util.DurationPart
import com.jethuit.spw.listenstats.util.durationParts
import com.jethuit.spw.listenstats.util.formatDuration
import com.jethuit.spw.listenstats.util.formatDurationCompact

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
}
