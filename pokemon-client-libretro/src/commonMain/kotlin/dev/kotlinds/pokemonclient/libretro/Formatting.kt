package dev.kotlinds.pokemonclient.libretro

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A small, common `String.format` (named `fmt`: on the JVM, the JDK's own `String.formatted` would shadow an
 * extension named `formatted`): the conversions the module's logs use, `%d`, `%s`, `%x`, `%X`, `%f` and `%%`, with
 * the flags `-` (left-justify), `+` (always a sign) and `0` (zero padding), a width and a precision
 * (`%08X`, `%-40s`, `%+5d`, `%+.3f`, `%4.1f`...). Hexadecimal shows the two's complement of negative numbers, like
 * the JVM's (`%08X` of -1 is `FFFFFFFF`).
 */
internal fun String.fmt(vararg args: Any?): String {
    val out = StringBuilder()
    var next = 0
    var i = 0
    while (i < length) {
        val c = this[i++]
        if (c != '%') { out.append(c); continue }
        val start = i
        while (i < length && this[i] in "-+0") i++
        val flags = substring(start, i)
        val widthStart = i
        while (i < length && this[i].isDigit()) i++
        val width = substring(widthStart, i).toIntOrNull() ?: 0
        var precision: Int? = null
        if (i < length && this[i] == '.') {
            val precisionStart = ++i
            while (i < length && this[i].isDigit()) i++
            precision = substring(precisionStart, i).toInt()
        }
        require(i < length) { "incomplete format specifier at the end of \"$this\"" }
        val conversion = this[i++]
        if (conversion == '%') { out.append('%'); continue }
        val arg = args[next++]
        val body = when (conversion) {
            'd' -> signed((arg as Number).toLong().toString(), '+' in flags)
            's' -> arg.toString().let { if (precision != null) it.take(precision) else it }
            'x', 'X' -> hex(arg as Number).let { if (conversion == 'X') it.uppercase() else it }
            'f' -> signed(fixed((arg as Number).toDouble(), precision ?: 6), '+' in flags)
            else -> throw IllegalArgumentException("unsupported conversion %$conversion in \"$this\"")
        }
        out.append(pad(body, width, leftJustify = '-' in flags, zeros = '0' in flags && conversion != 's'))
    }
    return out.toString()
}

private fun signed(text: String, plus: Boolean): String = if (plus && !text.startsWith('-') && text != "NaN") "+$text" else text

/** The unsigned hexadecimal of [value] at its own width (a Byte as 8 bits, an Int as 32...), lowercase. */
private fun hex(value: Number): String = when (value) {
    is Byte -> value.toUByte().toString(16)
    is Short -> value.toUShort().toString(16)
    is Int -> value.toUInt().toString(16)
    else -> value.toLong().toULong().toString(16)
}

/** [value] with exactly [decimals] digits after the point, rounded half away from zero. */
private fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    val scaled = (abs(value) * 10.0.pow(decimals)).roundToLong()
    val digits = scaled.toString().padStart(decimals + 1, '0')
    val text = if (decimals == 0) digits else digits.dropLast(decimals) + "." + digits.takeLast(decimals)
    return if (value < 0 && scaled != 0L) "-$text" else text
}

private fun pad(body: String, width: Int, leftJustify: Boolean, zeros: Boolean): String = when {
    body.length >= width -> body
    leftJustify -> body.padEnd(width)
    zeros -> {
        val sign = if (body.startsWith('-') || body.startsWith('+')) body.take(1) else ""
        sign + body.drop(sign.length).padStart(width - sign.length, '0')
    }
    else -> body.padStart(width)
}
