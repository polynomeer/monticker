package com.monticker.api.common.security

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DB 컬럼에 저장되는 민감한 값(브로커 API 키 등)을 AES-256-GCM으로 암복호화하는 JPA
 * AttributeConverter. `@Component`로 등록해 Spring이 관리하는 인스턴스를 Hibernate가
 * 재사용하도록 한다 — 그래야 `app.security.credential-encryption-key`를 생성자로 주입받을
 * 수 있다(Hibernate가 기본 생성자로 직접 new하는 컨버터는 설정값을 주입받지 못한다).
 *
 * 저장 형식: base64(IV(12바이트) || ciphertext || GCM 인증 태그(16바이트)).
 * 매 암호화마다 새 IV를 생성하므로 같은 평문이라도 저장값은 매번 달라진다(재사용 IV는
 * GCM의 기밀성을 깨뜨리므로 절대 고정하지 않는다).
 *
 * 적용 대상: BrokerageAccount.accessToken (docs/launch-plan.md Phase 0).
 */
@Component
@Converter
class EncryptedStringConverter(
    @Value("\${app.security.credential-encryption-key}") base64Key: String,
) : AttributeConverter<String?, String?> {

    private val secretKey = SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES")
    private val secureRandom = SecureRandom()

    init {
        require(secretKey.encoded.size == 32) {
            "app.security.credential-encryption-key는 Base64로 인코딩된 32바이트(AES-256) 키여야 합니다 " +
                "(실제 길이: ${secretKey.encoded.size}바이트). 생성: openssl rand -base64 32"
        }
    }

    override fun convertToDatabaseColumn(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    override fun convertToEntityAttribute(stored: String?): String? {
        if (stored.isNullOrEmpty()) return stored
        val decoded = Base64.getDecoder().decode(stored)
        val iv = decoded.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = decoded.copyOfRange(IV_LENGTH_BYTES, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
    }
}
