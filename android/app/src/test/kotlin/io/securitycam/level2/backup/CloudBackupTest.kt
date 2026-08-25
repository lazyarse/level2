package io.securitycam.level2.backup

import io.securitycam.level2.core.CloudBackupSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the cloud-backup plumbing (signer, keys, registry). */
class CloudBackupTest {

    @Test
    fun sigV4MatchesAwsPublishedTestVector() {
        // aws-sig-v4-test-suite / get-vanilla:
        // GET / host:example.amazonaws.com  x-amz-date:20150830T123600Z
        val signed = SigV4.sign(
            method = "GET",
            host = "example.amazonaws.com",
            canonicalUri = "/",
            canonicalQuery = "",
            region = "us-east-1",
            service = "service",
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            now = java.time.Instant.parse("2015-08-30T12:36:00Z"),
            payloadHash = SigV4.emptyPayloadHash(),
            contentSha256Header = false,
        )
        assertEquals("20150830T123600Z", signed.amzDate)
        assertEquals(
            "AWS4-HMAC-SHA256 " +
                "Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signed.authorizationHeader,
        )
    }

    @Test
    fun remoteKeyGroupsByCameraThenUtcDay() {
        val key = RemoteKeys.forMedia(
            cameraName = "Hallway",
            fileName = "2026-08-24_10-00-00-000_Hallway.mp4",
            atEpochMs = java.time.Instant.parse("2026-08-24T23:30:00Z").toEpochMilli(),
        )
        assertEquals("Hallway/2026-08-24/2026-08-24_10-00-00-000_Hallway.mp4", key)
    }

    @Test
    fun remoteKeySanitizesCameraNames() {
        val key = RemoteKeys.forMedia("Back Yard / Door!", "s.jpg", 0L)
        assertEquals("Back_Yard_Door/1970-01-01/s.jpg", key)
    }

    @Test
    fun plainHttpOnlyForPrivateHosts() {
        assertTrue(CloudUploaderRegistry.plainHttpAllowed("https://cloud.example.com/dav"))
        assertTrue(CloudUploaderRegistry.plainHttpAllowed("http://192.168.0.5:9000"))
        assertTrue(CloudUploaderRegistry.plainHttpAllowed("http://localhost:8080"))
        assertTrue(CloudUploaderRegistry.plainHttpAllowed("http://10.1.2.3/dav"))
        assertFalse(CloudUploaderRegistry.plainHttpAllowed("http://cloud.example.com/dav"))
        assertFalse(CloudUploaderRegistry.plainHttpAllowed("ftp://x"))
        assertFalse(CloudUploaderRegistry.plainHttpAllowed("not a url"))
    }

    @Test
    fun registryRequiresEnabledAndKnownBackend() {
        assertNull(CloudUploaderRegistry.forSettings(CloudBackupSettings(enabled = false)))
        assertNull(
            CloudUploaderRegistry.forSettings(
                CloudBackupSettings(enabled = true, backend = "gdrive", serverUrl = "https://x"),
            ),
        )
        assertTrue(
            CloudUploaderRegistry.forSettings(
                CloudBackupSettings(enabled = true, backend = "webdav", serverUrl = "https://x"),
            ) is WebDavUploader,
        )
        assertTrue(
            CloudUploaderRegistry.forSettings(
                CloudBackupSettings(enabled = true, backend = "s3", serverUrl = "https://s3.x"),
            ) is S3Uploader,
        )
    }
}
