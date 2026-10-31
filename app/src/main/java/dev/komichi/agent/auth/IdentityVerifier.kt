package dev.komichi.agent.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import dev.komichi.agent.data.obj
import org.json.JSONObject

object IdentityVerifier {
    fun verify(raw: String, clientId: String, nonce: String?, jwks: JWKSet, now: Long = System.currentTimeMillis()): JSONObject {
        require(raw.length < 32_768)
        val jwt = SignedJWT.parse(raw)
        val algorithm = jwt.header.algorithm
        require(algorithm == JWSAlgorithm.RS256 || algorithm == JWSAlgorithm.ES256) { "非対応の署名方式です" }
        require(!jwt.header.keyID.isNullOrBlank()) { "署名鍵IDがありません" }
        val key = jwks.getKeyByKeyId(jwt.header.keyID) ?: error("署名鍵を検証できません")
        require(key.keyUse == null || key.keyUse == KeyUse.SIGNATURE)
        val valid = when {
            algorithm == JWSAlgorithm.RS256 && key is RSAKey -> jwt.verify(RSASSAVerifier(key))
            algorithm == JWSAlgorithm.ES256 && key is ECKey -> jwt.verify(ECDSAVerifier(key))
            else -> false
        }
        require(valid) { "IDトークンの署名が不正です" }
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == AuthManager.ISSUER && clientId in claims.audience && !claims.subject.isNullOrBlank()) { "IDトークンの対象が不正です" }
        require(claims.expirationTime != null && claims.expirationTime.time > now - 5000 && claims.issueTime != null && claims.issueTime.time <= now + 5000) { "IDトークンの時刻が不正です" }
        require(claims.notBeforeTime == null || claims.notBeforeTime.time <= now + 5000)
        if (nonce != null) require(claims.getStringClaim("nonce") == nonce) { "認証nonceが一致しません" }
        return obj("sub" to claims.subject, "email" to (claims.getStringClaim("email") ?: "ChatGPT account"))
    }
}
