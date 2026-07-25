package io.github.styx798.sillytavernmanager.stmcore.installer

import java.security.MessageDigest
import java.security.PublicKey
import org.bouncycastle.math.ec.rfc8032.Ed25519

/**
 * API 31 does not expose an Ed25519 JCA Signature or KeyFactory implementation.
 *
 * STM therefore verifies the fixed RFC 8410 SubjectPublicKeyInfo encoding with Bouncy Castle's
 * lightweight Ed25519 primitive. No provider is installed or selected globally.
 */
internal object StmEd25519Verifier {
    fun verify(
        publicKey: PublicKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(signature.size == Ed25519.SIGNATURE_SIZE) {
            "An Ed25519 detached signature must be exactly ${Ed25519.SIGNATURE_SIZE} bytes"
        }
        val encoded = publicKey.encoded
            ?: error("The trusted Ed25519 public key has no X.509 encoding")
        require(encoded.size == SUBJECT_PUBLIC_KEY_INFO_PREFIX.size + Ed25519.PUBLIC_KEY_SIZE) {
            "The trusted Ed25519 public key has an invalid X.509 length"
        }
        require(
            MessageDigest.isEqual(
                SUBJECT_PUBLIC_KEY_INFO_PREFIX,
                encoded.copyOfRange(0, SUBJECT_PUBLIC_KEY_INFO_PREFIX.size),
            ),
        ) {
            "The trusted public key is not an RFC 8410 Ed25519 key"
        }
        val rawPublicKey = encoded.copyOfRange(
            SUBJECT_PUBLIC_KEY_INFO_PREFIX.size,
            encoded.size,
        )
        require(Ed25519.validatePublicKeyFull(rawPublicKey, 0)) {
            "The trusted Ed25519 public key is invalid"
        }
        return Ed25519.verify(
            signature,
            0,
            rawPublicKey,
            0,
            message,
            0,
            message.size,
        )
    }

    private val SUBJECT_PUBLIC_KEY_INFO_PREFIX = byteArrayOf(
        0x30,
        0x2a,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2b,
        0x65,
        0x70,
        0x03,
        0x21,
        0x00,
    )
}
