package ru.pm.messenger.storage

data class DetectedFile(
    val mimeType: String,
    val kind: String,
)

fun sniff(bytes: ByteArray): DetectedFile? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte()
    ) {
        return DetectedFile("image/png", "attachment")
    }
    if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return DetectedFile("image/jpeg", "attachment")
    }
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
    ) {
        return DetectedFile("image/webp", "attachment")
    }
    if (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
        return DetectedFile("application/pdf", "attachment")
    }
    if (bytes.size >= 9 && bytes.copyOfRange(0, 9).contentEquals("#!AMR-WB\n".toByteArray())) {
        return DetectedFile("audio/amr-wb", "voice")
    }
    if (bytes.size >= 6 && bytes.copyOfRange(0, 6).contentEquals("#!AMR\n".toByteArray())) {
        return DetectedFile("audio/amr", "voice")
    }
    if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("OggS".toByteArray())) {
        return DetectedFile("audio/ogg", "voice")
    }
    if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
        return DetectedFile("audio/mpeg", "voice")
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
        return DetectedFile("audio/mpeg", "voice")
    }
    if (bytes.size >= 12 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) {
        val brand = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
        if (brand == "M4A " || brand == "M4B ") {
            return DetectedFile("audio/mp4", "voice")
        }
    }
    return null
}

fun safeFileName(raw: String?): String {
    val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = base.replace(Regex("[\\p{Cntrl}\\\\/\"';]+"), "").trim().take(80)
    return cleaned.ifBlank { "file" }
}
