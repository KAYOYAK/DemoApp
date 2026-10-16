package com.kayoyak.demoapp.inference

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import java.util.Calendar

/**
 * Things a small model gets wrong, done by the app instead.
 *
 * ## Why these two
 *
 * Not because tools are a feature worth having, but because these are the two
 * questions where a small model's answer is *confidently* wrong:
 *
 * - **What time is it.** A model's weights stop at a date. It has no way to know
 *   what day it is, and it will answer anyway.
 * - **Arithmetic.** Multiplication of four-digit numbers is not something a 0.6
 *   billion parameter model does, and it produces a number that looks exactly
 *   like a right answer.
 *
 * Everything else a tool could do here -- search, fetch, write a file -- would
 * either need the network or step outside what this app is.
 *
 * ## What the model is told
 *
 * Descriptions are read by the model, so they are written for it: what the tool
 * answers, and when to reach for it. They are English because they are not part
 * of the interface -- the user never reads them, and models are trained on far
 * more English tool descriptions than Chinese ones. The values that come back are
 * digits either way.
 */
class LocalTools : ToolSet {

    /**
     * The current date and local time.
     *
     * Takes no arguments. A tool the model can parameterise is a tool it can
     * parameterise wrongly, and there is nothing useful to say here beyond "now".
     */
    @Tool(
        description = "Get the current date and local time on this device. " +
            "Use this whenever the answer depends on what day or time it is now, " +
            "including questions about today, tomorrow, or how long until something.",
    )
    fun currentDateTime(): String {
        val now = Calendar.getInstance()
        return buildString {
            append(now.get(Calendar.YEAR))
            append('-')
            append((now.get(Calendar.MONTH) + 1).toString().padStart(2, '0'))
            append('-')
            append(now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0'))
            append(' ')
            append(now.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0'))
            append(':')
            append(now.get(Calendar.MINUTE).toString().padStart(2, '0'))
        }
    }

    /**
     * Exact arithmetic.
     *
     * The expression is parsed, never evaluated as code. See [Calculator] for why
     * that distinction is the whole design.
     */
    @Tool(
        description = "Calculate an arithmetic expression exactly. " +
            "Use this for every sum, product, percentage, or power, however simple it " +
            "looks. Supports + - * / % ^ and brackets, and decimal numbers. " +
            "Example input: (2340 * 0.25) + 18",
    )
    fun calculate(expression: String): String =
        Calculator.evaluate(expression)
            ?: "That is not an arithmetic expression this can evaluate. " +
                "Use digits, + - * / % ^, decimals, and brackets."
}
