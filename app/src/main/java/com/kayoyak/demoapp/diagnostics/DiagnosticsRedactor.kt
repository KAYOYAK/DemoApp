package com.kayoyak.demoapp.diagnostics

/**
 * Removes anything identifying from text before it leaves the device.
 *
 * The report a user hands over is the only channel this app has. There is no
 * telemetry, so a bug is diagnosed entirely from what they choose to send -- which
 * means the report has to be worth sending *and* safe to send, and those pull
 * against each other. This is where that is settled: everything useful about the
 * failure stays, and everything about the person goes.
 *
 * ## Why filtering at export rather than at each log site
 *
 * Filtering on the way out covers text that was already written, including logs
 * added later by someone who did not know the rule. Filtering at the source is a
 * rule every future log line has to remember, and the failure mode is silent: a
 * leak nobody notices until it is in a public issue.
 *
 * ## What is removed, and why each one
 *
 * - **File paths.** Android paths contain the package name and sometimes a user
 *   name. They also leak which models someone downloaded, through the file name.
 * - **Addresses.** This app only ever contacts a model host, but a user's own
 *   network configuration can end up in a stack trace from the HTTP client.
 * - **Anything shaped like a credential.** The app holds none, and a report is
 *   not the place to discover that something upstream put one in a log line.
 * - **The conversation itself.** The most important one. A report is about the
 *   app failing, not about what was being discussed when it did, and the text of
 *   a private conversation ending up in a shared file is the exact outcome this
 *   app exists to prevent.
 *
 * Deliberately not a general "strip everything" pass: a report with no detail is
 * a report nobody can act on, and a user who sends one twice and gets nowhere
 * stops sending them.
 */
object DiagnosticsRedactor {

    /**
     * A pattern and what it becomes.
     *
     * Order matters. Longer, more specific patterns run first, so a URL is treated
     * as a URL before the address inside it is treated as an address.
     */
    private val rules: List<Rule> = listOf(
        // Credentials, before anything that could match inside them.
        Rule(
            Regex("""(?i)\b(bearer|token|api[_-]?key|password|passwd|secret)\b\s*[:=]?\s*\S+"""),
            "$1 <已隐去>",
        ),
        Rule(Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b"""), "<已隐去>"),
        Rule(Regex("""\bsk-[A-Za-z0-9_-]{16,}\b"""), "<已隐去>"),
        Rule(Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""), "<已隐去>"),
        // URLs, keeping the scheme so a failing endpoint is still identifiable.
        Rule(Regex("""\b(https?)://\S+"""), "$1://<已隐去>"),
        // Addresses, including host:port.
        Rule(
            Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b"""),
            "<地址已隐去>",
        ),
        Rule(Regex("""\b[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){3,7}\b"""), "<地址已隐去>"),
        // Paths. Both separators, and the common roots.
        Rule(Regex("""(?:/data|/storage|/sdcard|/mnt|/system|/cache)[/\w.\-]+"""), "<路径已隐去>"),
        Rule(Regex("""[A-Za-z]:\\[\\\w.\- ]+"""), "<路径已隐去>"),
        Rule(Regex("""/[\w.\-]+/[\w.\-/]{3,}"""), "<路径已隐去>"),
        // Addresses of the form someone@somewhere, which is a person.
        Rule(Regex("""\b[\w.+-]+@[\w-]+\.[\w.]+\b"""), "***@<已隐去>"),
        // Long opaque strings: a cache key, a signature, a session id.
        Rule(Regex("""\b[A-Za-z0-9+/=_-]{48,}\b"""), "<已隐去>"),
    )

    private data class Rule(val pattern: Regex, val replacement: String)

    /**
     * The text with everything identifying removed.
     *
     * Applied repeatedly until it stops changing. A single pass can leave a match
     * that the replacement itself created -- redacting a URL leaves the scheme,
     * and a rule that runs before another can expose a pattern the later one would
     * have caught. Two passes is the normal cost and the loop is bounded.
     */
    fun redact(text: String): String {
        var current = text
        repeat(MAX_PASSES) {
            var next = current
            rules.forEach { next = it.pattern.replace(next, it.replacement) }
            if (next == current) return next
            current = next
        }
        return current
    }

    /**
     * True when [text] still looks like it carries something identifying.
     *
     * Used by the tests as a property rather than an example, and available to the
     * report builder as a last check before a file is written. Not a guarantee --
     * no pattern list is -- and it is not treated as one.
     */
    fun looksUnredacted(text: String): Boolean =
        rules.any { it.pattern.containsMatchIn(text) }

    private const val MAX_PASSES = 4
}
