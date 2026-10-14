package ru.pm.messenger.storage

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import ru.pm.messenger.identity.currentPrincipal
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID

data class DownloadLinkView(
    val url: String,
    val expiresAt: OffsetDateTime,
)

@RestController
class AttachmentController(
    private val attachments: AttachmentService,
) {
    @PostMapping("/api/v1/conversations/{id}/attachments")
    fun upload(@PathVariable id: UUID, @RequestParam("file") file: MultipartFile): AttachmentView =
        attachments.upload(currentPrincipal(), id, file.originalFilename, file.inputStream)

    @PostMapping("/api/v1/attachments/{id}/link")
    fun link(@PathVariable id: UUID): DownloadLinkView {
        val issued = attachments.issueLink(currentPrincipal(), id)
        val base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().trimEnd('/')
        val url = "$base/api/v1/attachments/$id/content?ticket=${issued.ticket}"
        return DownloadLinkView(url, issued.expiresAt)
    }

    @GetMapping("/api/v1/attachments/{id}/content")
    fun content(
        @PathVariable id: UUID,
        @RequestParam(name = "ticket", required = false) ticket: String?,
    ): ResponseEntity<ByteArray> {
        val opened = attachments.open(id, ticket)
        val disposition = ContentDisposition.attachment()
            .filename(opened.originalName, StandardCharsets.UTF_8)
            .build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(opened.mimeType))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(opened.bytes)
    }
}
