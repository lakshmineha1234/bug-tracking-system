package com.bugtracker.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The file types that may be attached, decided by extension and confirmed from the file's first bytes.
 * The stored content type always comes from this list, never from the browser, and types that a browser
 * could run as a page (HTML, SVG, ...) are not allowed at all.
 */
public final class AttachmentTypes {

    private record Type(String contentType, byte[][] signatures) {
    }

    private static final byte[][] ANY = null;

    private static final Map<String, Type> TYPES = Map.ofEntries(
            Map.entry("png", new Type("image/png", sig(new byte[]{(byte) 0x89, 'P', 'N', 'G'}))),
            Map.entry("jpg", new Type("image/jpeg", sig(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))),
            Map.entry("jpeg", new Type("image/jpeg", sig(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))),
            Map.entry("gif", new Type("image/gif", sig(ascii("GIF87a"), ascii("GIF89a")))),
            Map.entry("webp", new Type("image/webp", sig(ascii("RIFF")))),
            Map.entry("pdf", new Type("application/pdf", sig(ascii("%PDF-")))),
            Map.entry("zip", new Type("application/zip", sig(new byte[]{'P', 'K', 3, 4}, new byte[]{'P', 'K', 5, 6}))),
            Map.entry("txt", new Type("text/plain", ANY)),
            Map.entry("log", new Type("text/plain", ANY)),
            Map.entry("csv", new Type("text/csv", ANY)),
            Map.entry("json", new Type("application/json", ANY)));

    private AttachmentTypes() {
    }

    public static String allowedExtensions() {
        return String.join(", ", new TreeSet<>(TYPES.keySet()));
    }

    /**
     * Returns the content type to store for this file, or empty if the extension is not allowed
     * or the contents do not match it.
     */
    public static Optional<String> detect(String filename, byte[] head) {
        String ext = extension(filename);
        Type type = TYPES.get(ext);
        if (type == null) {
            return Optional.empty();
        }
        if (type.signatures() != null && Arrays.stream(type.signatures()).noneMatch(s -> startsWith(head, s))) {
            return Optional.empty();
        }
        if (ext.equals("webp") && !(head.length >= 12 && new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WEBP"))) {
            return Optional.empty();
        }
        return Optional.of(type.contentType());
    }

    static String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[][] sig(byte[]... signatures) {
        return signatures;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
