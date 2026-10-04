package dev.codewise.tracker.crypto;

import dev.codewise.tracker.config.TrackerProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CodeCipherTest {

    @Test
    void shouldEncryptAndDecryptCode() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        CodeCipher cipher = new CodeCipher(new TrackerProperties("test-key", 1, key));
        String code = "class Solution { return 1; }";

        String encrypted = cipher.encrypt(code);

        assertNotEquals(code, encrypted);
        assertEquals(code, cipher.decrypt(encrypted));
    }
}
