package com.kayoyak.demoapp.inference

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Arithmetic a small model cannot do reliably in its head.
 *
 * ## Why this is hand-written
 *
 * There is no expression evaluator on the platform that is safe here. The usual
 * shortcut -- building a string and handing it to a scripting engine -- evaluates
 * whatever it is given, and what it is given comes from a language model, which
 * means it is text produced by something that has read the entire internet.
 * A parser that understands numbers, brackets, and five operators cannot do
 * anything else, however strange its input.
 *
 * ## Why decimals rather than doubles
 *
 * `0.1 + 0.2` is not `0.3` in binary floating point, and a user who asked a
 * calculator for that sum and got `0.30000000000000004` has been given a worse
 * answer than the model would have produced. Decimal arithmetic gets it right.
 *
 * ## What it deliberately does not do
 *
 * No variables, no functions, no assignment, no units. Every one of those is a
 * surface for the input to mean something other than what it looks like, and none
 * of them is needed to answer "what is 17 percent of 2,340".
 */
object Calculator {

    /** The largest exponent allowed, so `9^9^9` is a refusal rather than a hang. */
    private const val MAX_EXPONENT = 10_000

    /** How deep brackets may nest, so a wall of `(` fails fast. */
    private const val MAX_DEPTH = 32

    /** Significant digits in division; enough that rounding is not the answer's story. */
    private val CONTEXT = MathContext(34, RoundingMode.HALF_UP)

    /**
     * Evaluates [expression], or returns null when it is not one.
     *
     * Null rather than an exception: the caller is a tool call from a language
     * model, and "I could not read that" is a normal answer to give back, not an
     * error to throw.
     */
    fun evaluate(expression: String): String? {
        val parser = Parser(expression)
        val value = runCatching { parser.parse() }.getOrNull() ?: return null
        if (!parser.atEnd()) return null
        return format(value)
    }

    /** Plain decimal, without an exponent and without trailing zeroes. */
    private fun format(value: BigDecimal): String =
        value.stripTrailingZeros().let {
            // `stripTrailingZeros` turns 100 into 1E+2, which is a correct number
            // written in a way nobody asked for.
            if (it.scale() < 0) it.setScale(0) else it
        }.toPlainString()

    private class Parser(private val text: String) {
        private var index = 0
        private var depth = 0

        fun atEnd(): Boolean {
            skipSpaces()
            return index >= text.length
        }

        fun parse(): BigDecimal {
            val value = expression()
            return value
        }

        /** Sum and difference, left to right. */
        private fun expression(): BigDecimal {
            var value = term()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '+' -> { index++; value = value.add(term(), CONTEXT) }
                    '-' -> { index++; value = value.subtract(term(), CONTEXT) }
                    else -> return value
                }
            }
        }

        /** Product, quotient and remainder, left to right. */
        private fun term(): BigDecimal {
            var value = power()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '*' -> { index++; value = value.multiply(power(), CONTEXT) }
                    '/' -> {
                        index++
                        val divisor = power()
                        if (divisor.signum() == 0) throw Refused()
                        value = value.divide(divisor, CONTEXT)
                    }
                    '%' -> {
                        index++
                        val divisor = power()
                        if (divisor.signum() == 0) throw Refused()
                        value = value.remainder(divisor, CONTEXT)
                    }
                    else -> return value
                }
            }
        }

        /** Power, right-associative, so `2^3^2` is `2^9`. */
        private fun power(): BigDecimal {
            val base = unary()
            skipSpaces()
            if (peek() != '^') return base
            index++
            val exponent = power()
            // Tested for a fractional part before rounding, not after. Rounding
            // first turns 0.5 into 1, and 4^0.5 quietly becomes 4 -- a wrong
            // answer that nothing on screen distinguishes from the right one.
            if (exponent.stripTrailingZeros().scale() > 0) {
                throw Refused()
            }
            val asInt = runCatching {
                exponent.setScale(0, RoundingMode.UNNECESSARY).intValueExact()
            }.getOrNull() ?: throw Refused()
            if (asInt < 0 || asInt > MAX_EXPONENT) {
                throw Refused()
            }
            return base.pow(asInt, CONTEXT)
        }

        /** A leading sign, which binds tighter than anything to its right. */
        private fun unary(): BigDecimal {
            skipSpaces()
            return when (peek()) {
                '-' -> { index++; unary().negate() }
                '+' -> { index++; unary() }
                else -> atom()
            }
        }

        private fun atom(): BigDecimal {
            skipSpaces()
            return when (peek()) {
                '(' -> {
                    if (++depth > MAX_DEPTH) throw Refused()
                    index++
                    val value = expression()
                    skipSpaces()
                    if (peek() != ')') throw Refused()
                    index++
                    depth--
                    value
                }
                else -> number()
            }
        }

        private fun number(): BigDecimal {
            skipSpaces()
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            // Digit grouping, because a question about money is written with it.
            if (index < text.length && text[index] == ',') {
                while (index < text.length && (text[index].isDigit() || text[index] == ',')) index++
            }
            val raw = text.substring(start, index).replace(",", "")
            if (raw.isEmpty() || raw == ".") throw Refused()
            return BigDecimal(raw)
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun skipSpaces() {
            while (index < text.length && text[index] == ' ') index++
        }
    }
}

/**
 * Thrown for every input this will not evaluate. Caught by [Calculator.evaluate].
 *
 * ## What used to be here, and why it is not
 *
 * A `Refusal` enum — `DIVIDE_BY_ZERO`, `TOO_DEEP`, `UNCLOSED_BRACKET` and three
 * more — carried on this exception, with a doc comment claiming "a code is also
 * the thing a caller can branch on".
 *
 * No caller branched on it. Every refusal reaches the model as one sentence
 * ("That is not an arithmetic expression this can evaluate…"), which is the right
 * behaviour: the model asked a question and needs to know it cannot be answered,
 * not which production rule rejected it. `evaluate` returns null for all six, and
 * the tests assert null for all six, so nothing anywhere distinguished them.
 *
 * Removing it changes no behaviour and deletes the claim that something reads it.
 * The exception stays because a named type is what the parser throws and what
 * [Calculator.evaluate] catches — the distinction that was not earning its keep
 * was the reason code, not the fact of refusal.
 */
class Refused : Exception()