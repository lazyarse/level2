package io.securitycam.level2.backup

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AWS Signature Version 4 signer (pure JVM, no SDK). Supports the
 * subset cloud backup needs: single-shot requests with UNSIGNED-PAYLOAD and
 * a fixed header set.
 */
object SigV4 {

    data class SignedRequest(
        val authorizationHeader: String,
        val amzDate: String,
        val payloadHash: String,
    )

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun signingKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmacSha256("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    /**
     * Signs [method] against [canonicalUri]/[canonicalQuery] with the given
     * extra headers (lower-cased names). Returns headers to attach.
     */
    fun sign(
        method: String,
        host: String,
        canonicalUri: String,
        canonicalQuery: String,
        region: String,
        service: String,
        accessKeyId: String,
        secretAccessKey: String,
        extraHeaders: Map<String, String> = emptyMap(),
        now: java.time.Instant = java.time.Instant.now(),
        payloadHash: String = UNSIGNED_PAYLOAD,
        /** S3 requires x-amz-content-sha256 signed; other services don't send it. */
        contentSha256Header: Boolean = true,
    ): SignedRequest {
        val amzDate = DateTimeFormatter.format(now)
        val dateStamp = amzDate.substringBefore('T')

        val headers = sortedMapOf<String, String>(
            "host" to host,
            "x-amz-date" to amzDate,
        )
        if (contentSha256Header) {
            headers["x-amz-content-sha256"] = payloadHash
        }
        for ((k, v) in extraHeaders) {
            headers[k.lowercase().trim()] = v.trim()
        }

        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { (k, v) ->
            "$k:$v\n"
        }

        val canonicalRequest = listOf(
            method.uppercase(),
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            scope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val signature = hmacSha256(signingKey(secretAccessKey, dateStamp, region, service), stringToSign)
            .joinToString("") { "%02x".format(it) }

        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
        return SignedRequest(authorization, amzDate, payloadHash)
    }

    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    /** SHA-256 of the empty body — the canonical-request payload hash for GET/HEAD. */
    fun emptyPayloadHash(): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(ByteArray(0))
            .joinToString("") { "%02x".format(it) }

    /** `yyyyMMdd'T'HHmmss'Z'` in UTC. */
    private object DateTimeFormatter {
        fun format(instant: java.time.Instant): String =
            java.time.ZoneOffset.UTC.let { utc ->
                val t = java.time.ZonedDateTime.ofInstant(instant, utc)
                "%04d%02d%02dT%02d%02d%02dZ".format(
                    t.year, t.monthValue, t.dayOfMonth,
                    t.hour, t.minute, t.second,
                )
            }
    }
}
