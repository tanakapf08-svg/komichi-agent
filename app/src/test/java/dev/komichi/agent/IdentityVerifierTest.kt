package dev.komichi.agent

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.komichi.agent.auth.AuthManager
import dev.komichi.agent.auth.IdentityVerifier
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class IdentityVerifierTest {
    private val key = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val now = System.currentTimeMillis()
    private fun token(issuer: String = AuthManager.ISSUER, audience: String = "oaiapp_test", nonce: String = "nonce_one", expiry: Long = now + 60_000): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("test-subject").issueTime(Date(now)).expirationTime(Date(expiry)).claim("nonce",nonce).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(),claims).apply { sign(RSASSASigner(key)) }.serialize()
    }
    @Test fun acceptsOnlySignedMatchingIdentity() {
        val identity = IdentityVerifier.verify(token(),"oaiapp_test","nonce_one",JWKSet(key.toPublicJWK()),now)
        assertEquals("test-subject",identity.getString("sub"))
    }
    @Test fun rejectsWrongNonceAudienceIssuerExpiredAndForgedSignature() {
        val jwks = JWKSet(key.toPublicJWK())
        listOf(token(nonce = "wrong"),token(audience = "other-app"),token(issuer = "https://evil.example"),token(expiry = now - 10_000)).forEach {
            assertTrue(runCatching { IdentityVerifier.verify(it,"oaiapp_test","nonce_one",jwks,now) }.isFailure)
        }
        val attacker = RSAKeyGenerator(2048).keyID(key.keyID).generate()
        assertTrue(runCatching { IdentityVerifier.verify(token(),"oaiapp_test","nonce_one",JWKSet(attacker.toPublicJWK()),now) }.isFailure)
    }
}
