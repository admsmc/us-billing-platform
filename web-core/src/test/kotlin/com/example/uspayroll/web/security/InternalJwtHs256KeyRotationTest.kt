package com.example.uspayroll.web.security

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class InternalJwtHs256KeyRotationTest {

    @Test
    fun `verifyWithKeyring accepts token signed with any configured kid`() {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        val tokenOld = InternalJwtHs256.issue(
            secret = "old-secret",
            issuer = "iss",
            subject = "sub",
            audience = "aud",
            ttlSeconds = 60,
            now = now,
            kid = "old",
        )
        val tokenNew = InternalJwtHs256.issue(
            secret = "new-secret",
            issuer = "iss",
            subject = "sub",
            audience = "aud",
            ttlSeconds = 60,
            now = now,
            kid = "new",
        )

        val keyring = mapOf(
            "old" to "old-secret",
            "new" to "new-secret",
        )

        val v1 = InternalJwtHs256.verifyWithKeyring(
            token = tokenOld,
            secretsByKid = keyring,
            expectedIssuer = "iss",
            expectedAudience = "aud",
            now = now,
        )
        val v2 = InternalJwtHs256.verifyWithKeyring(
            token = tokenNew,
            secretsByKid = keyring,
            expectedIssuer = "iss",
            expectedAudience = "aud",
            now = now,
        )

        assertEquals("old", v1.keyId)
        assertEquals("new", v2.keyId)
    }

    @Test
    fun `verifyWithKeyring rejects unknown kid`() {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        val token = InternalJwtHs256.issue(
            secret = "old-secret",
            issuer = "iss",
            subject = "sub",
            audience = "aud",
            ttlSeconds = 60,
            now = now,
            kid = "old",
        )

        assertFails {
            InternalJwtHs256.verifyWithKeyring(
                token = token,
                secretsByKid = mapOf("new" to "new-secret"),
                expectedIssuer = "iss",
                expectedAudience = "aud",
                now = now,
            )
        }
    }
}
