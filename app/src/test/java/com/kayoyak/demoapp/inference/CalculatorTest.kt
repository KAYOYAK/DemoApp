package com.kayoyak.demoapp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Arithmetic for a model to call.
 *
 * The input is text produced by a language model, so the tests are mostly about
 * what it cannot be made to do rather than what it computes. A parser that only
 * understands numbers, brackets and five operators cannot be talked into anything
 * else, and these are the tests that say so.
 */
class CalculatorTest {

    // ------------------------------------------------------------ arithmetic ---

    @Test
    fun `addition and subtraction`() {
        assertEquals("7", Calculator.evaluate("3 + 4"))
        assertEquals("-1", Calculator.evaluate("3 - 4"))
        assertEquals("10", Calculator.evaluate("1 + 2 + 3 + 4"))
    }

    @Test
    fun `multiplication and division`() {
        assertEquals("12", Calculator.evaluate("3 * 4"))
        assertEquals("3", Calculator.evaluate("12 / 4"))
        assertEquals("24", Calculator.evaluate("2 * 3 * 4"))
    }

    @Test
    fun `precedence follows arithmetic, not reading order`() {
        // The whole reason a model should call this rather than answer itself.
        assertEquals("14", Calculator.evaluate("2 + 3 * 4"))
        assertEquals("20", Calculator.evaluate("(2 + 3) * 4"))
    }

    @Test
    fun `decimals are exact`() {
        // 0.1 + 0.2 is not 0.3 in binary floating point. A user who asked for this
        // sum and got 0.30000000000000004 has been given a worse answer than the
        // model would have produced on its own.
        assertEquals("0.3", Calculator.evaluate("0.1 + 0.2"))
    }

    @Test
    fun `money is written the way money is written`() {
        assertEquals("585", Calculator.evaluate("2340 * 0.25"))
        assertEquals("2340", Calculator.evaluate("2,340"))
    }

    @Test
    fun `powers are right-associative`() {
        // 2^3^2 is 2^9, not 8^2. Getting this backwards changes the answer and
        // nothing on screen would say which was meant.
        assertEquals("512", Calculator.evaluate("2^3^2"))
        assertEquals("8", Calculator.evaluate("2^3"))
    }

    @Test
    fun `a negative exponent is refused rather than guessed`() {
        // The exact answer is a fraction. Returning a rounded one would be a
        // number the model then states as fact.
        assertNull(Calculator.evaluate("2^-1"))
    }

    @Test
    fun `a non-integer exponent is refused`() {
        assertNull(Calculator.evaluate("4^0.5"))
    }

    @Test
    fun `remainder`() {
        assertEquals("1", Calculator.evaluate("10 % 3"))
    }

    // ---------------------------------------------------- what it must refuse ---

    @Test
    fun `an empty expression is not a number`() {
        assertNull(Calculator.evaluate(""))
        assertNull(Calculator.evaluate("   "))
    }

    @Test
    fun `unparseable text is refused, not approximated`() {
        // The input comes from a model, so it will sometimes be words. Reading the
        // digits out of "about 3 or 4" and answering 3 would be inventing.
        assertNull(Calculator.evaluate("about 3 or 4"))
        assertNull(Calculator.evaluate("hello"))
        assertNull(Calculator.evaluate("1 +"))
    }

    @Test
    fun `trailing rubbish invalidates the whole expression`() {
        // Parsing a valid prefix and ignoring the rest is how "1+1, or maybe 5"
        // comes back as 2.
        assertNull(Calculator.evaluate("1 + 1 potatoes"))
    }

    @Test
    fun `an unclosed bracket is refused`() {
        assertNull(Calculator.evaluate("(1 + 2"))
        assertNull(Calculator.evaluate("1 + 2)"))
    }

    @Test
    fun `division by zero is refused rather than infinite`() {
        assertNull(Calculator.evaluate("1 / 0"))
        assertNull(Calculator.evaluate("1 % 0"))
    }

    @Test
    fun `a wall of brackets fails instead of hanging`() {
        val deep = "(".repeat(500) + "1" + ")".repeat(500)

        assertNull(Calculator.evaluate(deep))
    }

    @Test
    fun `an absurd exponent is refused rather than computed`() {
        // 9^9^9 is a number with hundreds of millions of digits. Without a ceiling
        // this is a memory exhaustion, reached by asking nicely.
        assertNull(Calculator.evaluate("9^99999999"))
    }

    @Test
    fun `code-shaped input is refused`() {
        // The reason there is a parser here and not a scripting engine. None of
        // these is arithmetic, and none of them can be reached.
        assertNull(Calculator.evaluate("Runtime.getRuntime().exec(\"ls\")"))
        assertNull(Calculator.evaluate("print(1)"))
        assertNull(Calculator.evaluate("1; System.exit(0)"))
        assertNull(Calculator.evaluate("\${7*7}"))
        assertNull(Calculator.evaluate("__import__('os').system('ls')"))
    }

    @Test
    fun `letters are not numbers`() {
        // `e` and `x` are letters, and a parser that accepted scientific notation
        // by accident would also accept them inside an identifier.
        assertNull(Calculator.evaluate("1e5"))
        assertNull(Calculator.evaluate("0x10"))
    }

    // ------------------------------------------------------------- formatting ---

    @Test
    fun `a whole number is written without a decimal point`() {
        assertEquals("100", Calculator.evaluate("50 * 2"))
        assertEquals("4", Calculator.evaluate("2 + 2"))
    }

    @Test
    fun `no exponent notation in the answer`() {
        // stripTrailingZeros turns 100 into 1E+2, which is correct and unreadable.
        assertEquals("1000000", Calculator.evaluate("1000 * 1000"))
    }

    @Test
    fun `a repeating division is rounded, not truncated to a sentence`() {
        val third = Calculator.evaluate("1 / 3")

        assertEquals("0.3333333333333333333333333333333333", third)
    }

    @Test
    fun `spaces are optional`() {
        assertEquals("14", Calculator.evaluate("2+3*4"))
    }
}
