package com.monticker.api.common.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class EncryptedStringConverterTest {

    private val validKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val converter = EncryptedStringConverter(validKey)

    @Test
    fun `round-trips a plaintext value through encrypt then decrypt`() {
        val plain = "kis-app-secret-1234567890"

        val stored = converter.convertToDatabaseColumn(plain)
        val recovered = converter.convertToEntityAttribute(stored)

        assertThat(stored).isNotEqualTo(plain)
        assertThat(recovered).isEqualTo(plain)
    }

    @Test
    fun `never stores the plaintext as a substring of the ciphertext`() {
        val plain = "super-secret-broker-token"
        val stored = converter.convertToDatabaseColumn(plain)!!
        assertThat(stored).doesNotContain(plain)
    }

    @Test
    fun `encrypting the same plaintext twice produces different ciphertext (random IV)`() {
        val plain = "same-token-both-times"
        val first = converter.convertToDatabaseColumn(plain)
        val second = converter.convertToDatabaseColumn(plain)

        assertThat(first).isNotEqualTo(second)
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo(plain)
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo(plain)
    }

    @Test
    fun `passes null and empty strings through unchanged instead of encrypting them`() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull()
        assertThat(converter.convertToEntityAttribute(null)).isNull()
        assertThat(converter.convertToDatabaseColumn("")).isEmpty()
        assertThat(converter.convertToEntityAttribute("")).isEmpty()
    }

    @Test
    fun `rejects a key that is not exactly 32 bytes once Base64-decoded`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThatThrownBy { EncryptedStringConverter(shortKey) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32바이트")
    }
}
