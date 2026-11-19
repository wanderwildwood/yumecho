package com.wanderwildwood.yumecho.cloud

import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's account signs in with the login name and builds paths with the user id. */
class AccountPathTest {
    @Test fun pathUsesUidNotLoginName() {
        val account = Account("https://cloud.example.org/", "Ada@Example.org", "pw", "ada")
        assertEquals("https://cloud.example.org/remote.php/dav/files/ada/Dream%20Log/", DreamFolder(account, "Dream Log").folderUrl())
    }

    @Test fun ownSignInUidIsLoginName() {
        val account = Account("https://cloud.example.org", "ada", "pw")
        assertEquals("ada", account.uid)
        assertEquals("https://cloud.example.org/remote.php/dav/files/ada/x/", DreamFolder(account, "x").folderUrl())
    }

    @Test fun probeSignsInWithLoginNameOnUidPath() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
        server.start()
        try {
            val account = Account(server.url("/").toString(), "Ada@Example.org", "pw", "ada")
            DreamFolder(account, "Dream Log").probe()
            val request = server.takeRequest()
            assertEquals("/remote.php/dav/files/ada/", request.path)
            assertEquals(Credentials.basic("Ada@Example.org", "pw", Charsets.UTF_8), request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
