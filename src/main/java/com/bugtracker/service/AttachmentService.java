package com.bugtracker.service;

import com.bugtracker.dto.AttachmentResponse;
import com.bugtracker.entity.Attachment;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.exception.BadRequestException;
import com.bugtracker.exception.ForbiddenException;
import com.bugtracker.exception.NotFoundException;
import com.bugtracker.repository.AttachmentRepository;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Attachments on bugs. Anyone who can see a bug can list, download and add attachments;
 * only the uploader or an admin can delete one. File contents are kept on disk under random names,
 * and the database row holds the original name, type and size.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final int HEAD_BYTES = 16;

    /** A stored file ready to be sent to the browser. */
    public record StoredFile(String filename, String contentType, long sizeBytes, boolean image, Path path) {
    }

    private final AttachmentRepository attachments;
    private final BugRepository bugs;
    private final CurrentUserService currentUser;
    private final Path root;

    public AttachmentService(AttachmentRepository attachments, BugRepository bugs, CurrentUserService currentUser,
                             @Value("${app.attachments.dir:data/attachments}") String dir) {
        this.attachments = attachments;
        this.bugs = bugs;
        this.currentUser = currentUser;
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(Long bugId) {
        User me = currentUser.get();
        findVisibleBug(bugId, me);
        return attachments.findByBugIdOrderByIdAsc(bugId).stream()
                .map(a -> AttachmentResponse.from(a, canDelete(me, a)))
                .toList();
    }

    @Transactional
    public AttachmentResponse upload(Long bugId, MultipartFile file) {
        User me = currentUser.get();
        Bug bug = findVisibleBug(bugId, me);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Choose a file to upload");
        }
        String filename = cleanFilename(file.getOriginalFilename());
        String contentType = AttachmentTypes.detect(filename, readHead(file)).orElseThrow(() -> new BadRequestException(
                "This file type is not allowed, or the file does not match its extension. Allowed types: "
                        + AttachmentTypes.allowedExtensions()));

        String key = UUID.randomUUID().toString().replace("-", "");
        Path target = root.resolve(key);
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(root);
            Files.copy(in, target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store attachment", e);
        }
        // If the database change is rolled back, remove the file so no orphan is left behind.
        afterTransaction(false, target);
        Attachment saved = attachments.save(new Attachment(bug, me, filename, contentType, file.getSize(), key));
        return AttachmentResponse.from(saved, true);
    }

    @Transactional(readOnly = true)
    public StoredFile download(Long attachmentId) {
        User me = currentUser.get();
        Attachment a = findVisibleAttachment(attachmentId, me);
        Path path = root.resolve(a.getStorageKey());
        if (!Files.isRegularFile(path)) {
            log.warn("Attachment {} is in the database but its file {} is missing", a.getId(), path);
            throw new NotFoundException("The file for this attachment is missing");
        }
        return new StoredFile(a.getOriginalFilename(), a.getContentType(), a.getSizeBytes(), a.isImage(), path);
    }

    @Transactional
    public void delete(Long attachmentId) {
        User me = currentUser.get();
        Attachment a = findVisibleAttachment(attachmentId, me);
        if (!canDelete(me, a)) {
            throw new ForbiddenException("Only the uploader or an admin can delete this attachment");
        }
        attachments.delete(a);
        // Remove the file only once the row is really gone.
        afterTransaction(true, root.resolve(a.getStorageKey()));
    }

    private static boolean canDelete(User me, Attachment a) {
        return me.getRole() == Role.ADMIN || a.getUploadedBy().getId().equals(me.getId());
    }

    private Bug findVisibleBug(Long bugId, User me) {
        Bug bug = bugs.findById(bugId).orElseThrow(() -> new NotFoundException("Bug " + bugId + " not found"));
        if (!BugPermissions.canView(me, bug)) {
            throw new ForbiddenException("You do not have access to bug " + bugId);
        }
        return bug;
    }

    private Attachment findVisibleAttachment(Long attachmentId, User me) {
        Attachment a = attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment " + attachmentId + " not found"));
        if (!BugPermissions.canView(me, a.getBug())) {
            throw new ForbiddenException("You do not have access to this attachment");
        }
        return a;
    }

    /** Deletes the file after the transaction commits (onCommit) or after it rolls back (!onCommit). */
    private static void afterTransaction(boolean onCommit, Path file) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                boolean committed = status == STATUS_COMMITTED;
                if (committed == onCommit) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        log.warn("Could not delete attachment file {}: {}", file, e.getMessage());
                    }
                }
            }
        });
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read upload", e);
        }
    }

    /** Keeps only the file's own name (no folders), without control characters, at most 255 characters. */
    static String cleanFilename(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty()) {
            name = "file";
        }
        if (name.length() > 255) {
            String ext = AttachmentTypes.extension(name);
            name = name.substring(0, 255 - ext.length() - 1) + "." + ext;
        }
        return name;
    }
}
