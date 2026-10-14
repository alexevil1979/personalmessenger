package ru.pm.messenger.web

object LogRedactor {
    private val assignment = Regex(
        """(?i)(password|passwd|token|secret|private[_-]?key|ciphertext|refresh)\s*[:=]\s*\S+""",
    )
    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""")

    fun redact(value: String): String =
        bearer.replace(assignment.replace(value) { "${it.groupValues[1]}=[redacted]" }, "Bearer [redacted]")
}
