package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.exception.ReceiptInvalidFileException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

@Component
public class ReceiptFileValidator {

    private static final int MAX_FILENAME = 255;
    private static final int SNIFF_BYTES = 512;

    public ValidatedReceiptFile validate(MultipartFile file, long maxFileSizeBytes) {
        if (file == null || file.isEmpty()) {
            throw new ReceiptInvalidFileException("Receipt file is required");
        }
        if (file.getSize() <= 0 || file.getSize() > maxFileSizeBytes) {
            throw new ReceiptInvalidFileException("Receipt file exceeds the allowed size");
        }

        String sanitizedFilename = sanitizeFilename(file.getOriginalFilename());
        DigestResult digestResult = digest(file);
        if (digestResult.size() != file.getSize()) {
            throw new ReceiptInvalidFileException("Receipt file size could not be validated");
        }

        DetectedContent detected = detect(digestResult.sniffed());
        if (detected == null) {
            throw new ReceiptInvalidFileException("Receipt file type is not supported");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (contentType != null && !detected.allowedClientContentTypes().contains(contentType)) {
            throw new ReceiptInvalidFileException("Receipt content type does not match file content");
        }

        String objectFilename = detected.extension() + "-" + sanitizedFilename;
        return new ValidatedReceiptFile(
                digestResult.sha256Hex(),
                detected.contentType(),
                objectFilename,
                digestResult.size()
        );
    }

    private DigestResult digest(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sniffed = new byte[SNIFF_BYTES];
            int sniffedLength = 0;
            long total = 0;
            try (InputStream raw = file.getInputStream();
                 DigestInputStream input = new DigestInputStream(raw, digest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (sniffedLength < SNIFF_BYTES) {
                        int toCopy = Math.min(read, SNIFF_BYTES - sniffedLength);
                        System.arraycopy(buffer, 0, sniffed, sniffedLength, toCopy);
                        sniffedLength += toCopy;
                    }
                    total += read;
                }
            }
            byte[] sniffedBytes = new byte[sniffedLength];
            System.arraycopy(sniffed, 0, sniffedBytes, 0, sniffedLength);
            return new DigestResult(HexFormat.of().formatHex(digest.digest()), total, sniffedBytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        } catch (IOException ex) {
            throw new ReceiptInvalidFileException("Receipt file could not be read");
        }
    }

    private static String sanitizeFilename(String originalFilename) {
        String name = Objects.requireNonNullElse(originalFilename, "receipt");
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\r\\n\\t\\u0000]", "");
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        name = name.replaceAll("_+", "_");
        name = name.replaceAll("^\\.+", "");
        name = name.replaceAll("\\.+$", "");
        if (name.isBlank()) {
            name = "receipt";
        }
        if (name.length() > MAX_FILENAME) {
            name = name.substring(0, MAX_FILENAME);
        }
        return name;
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).trim();
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon).trim();
        }
        return normalized;
    }

    private static DetectedContent detect(byte[] bytes) {
        if (isPdf(bytes)) {
            return new DetectedContent("application/pdf", "pdf", java.util.Set.of("application/pdf"));
        }
        if (isPng(bytes)) {
            return new DetectedContent("image/png", "png", java.util.Set.of("image/png"));
        }
        if (isJpeg(bytes)) {
            return new DetectedContent("image/jpeg", "jpg", java.util.Set.of("image/jpeg", "image/jpg"));
        }
        return null;
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private record DigestResult(String sha256Hex, long size, byte[] sniffed) {
    }

    private record DetectedContent(String contentType, String extension, java.util.Set<String> allowedClientContentTypes) {
    }

    public record ValidatedReceiptFile(
            String sha256Hex,
            String contentType,
            String originalFilename,
            long sizeBytes
    ) {
    }
}
