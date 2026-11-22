package com.banco.transfer_service.transfer.receipt.service;

import com.banco.transfer_service.transfer.receipt.exception.ReceiptInvalidFileException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptFileValidatorTest {

    private final ReceiptFileValidator validator = new ReceiptFileValidator();

    @Test
    void validate_acceptsPdfAndSanitizesFilename() {
        byte[] content = "%PDF-1.4\n%test".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../evil receipt.pdf", "application/pdf", content
        );

        ReceiptFileValidator.ValidatedReceiptFile validated = validator.validate(file, 5 * 1024 * 1024);

        assertThat(validated.contentType()).isEqualTo("application/pdf");
        assertThat(validated.originalFilename()).doesNotContain("..").doesNotContain("/");
        assertThat(validated.sha256Hex()).hasSize(64);
        assertThat(validated.sizeBytes()).isEqualTo(content.length);
    }

    @Test
    void validate_acceptsPng() {
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2};
        MockMultipartFile file = new MockMultipartFile("file", "ok.png", "image/png", png);

        ReceiptFileValidator.ValidatedReceiptFile validated = validator.validate(file, 5 * 1024 * 1024);

        assertThat(validated.contentType()).isEqualTo("image/png");
    }

    @Test
    void validate_acceptsJpeg() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x10};
        MockMultipartFile file = new MockMultipartFile("file", "ok.jpg", "image/jpeg", jpeg);

        ReceiptFileValidator.ValidatedReceiptFile validated = validator.validate(file, 5 * 1024 * 1024);

        assertThat(validated.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void validate_rejectsUnsupportedType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> validator.validate(file, 5 * 1024 * 1024))
                .isInstanceOf(ReceiptInvalidFileException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void validate_rejectsMismatchedContentType() {
        byte[] pdf = "%PDF-1.4\n%test".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "bad.pdf", "image/png", pdf);

        assertThatThrownBy(() -> validator.validate(file, 5 * 1024 * 1024))
                .isInstanceOf(ReceiptInvalidFileException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void validate_rejectsOversizedFile() {
        byte[] content = "%PDF-1.4\n%test".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", content);

        assertThatThrownBy(() -> validator.validate(file, 4))
                .isInstanceOf(ReceiptInvalidFileException.class)
                .hasMessageContaining("exceeds");
    }
}
