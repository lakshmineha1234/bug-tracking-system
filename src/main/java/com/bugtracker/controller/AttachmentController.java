package com.bugtracker.controller;

import com.bugtracker.dto.AttachmentResponse;
import com.bugtracker.service.AttachmentService;
import com.bugtracker.service.AttachmentService.StoredFile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping("/api/bugs/{bugId}/attachments")
    public List<AttachmentResponse> list(@PathVariable Long bugId) {
        return attachmentService.list(bugId);
    }

    /** Upload one file as multipart/form-data in the "file" part. */
    @PostMapping(path = "/api/bugs/{bugId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@PathVariable Long bugId, @RequestParam("file") MultipartFile file) {
        return attachmentService.upload(bugId, file);
    }

    /**
     * Downloads the file. With inline=true an image is shown in the page; every other type is always
     * sent as a download so the browser never opens it as a page.
     */
    @GetMapping("/api/attachments/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @RequestParam(defaultValue = "false") boolean inline) {
        StoredFile file = attachmentService.download(id);
        ContentDisposition disposition = (inline && file.image() ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; sandbox")
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(new FileSystemResource(file.path()));
    }

    @DeleteMapping("/api/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        attachmentService.delete(id);
    }
}
