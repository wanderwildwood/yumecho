package com.wanderwildwood.yumecho.cloud

import com.wanderwildwood.yumecho.dreams.Dream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** A small WebDAV server in memory: folders, files, and the four verbs the app uses. */
private class FakeDav(val user: String, val password: String) : Dispatcher() {
    val files = linkedMapOf<String, String>()
    val folders = mutableSetOf<String>()
    val log = mutableListOf<String>()
    val base = "/remote.php/dav/files/$user/"

    override fun dispatch(request: RecordedRequest): MockResponse {
        log += "${request.method} ${request.path}"
        if (request.getHeader("Authorization") != Credentials.basic(user, password, Charsets.UTF_8)) {
            return MockResponse().setResponseCode(401)
        }
        val path = java.net.URLDecoder.decode(request.path!!.removePrefix(base), "UTF-8").trimEnd('/')
        return when (request.method) {
            "MKCOL" -> {
                val parent = path.substringBeforeLast('/', "")
                when {
                    path in folders -> MockResponse().setResponseCode(405)
                    parent.isNotEmpty() && parent !in folders -> MockResponse().setResponseCode(409)
                    else -> { folders += path; MockResponse().setResponseCode(201) }
                }
            }
            "PUT" -> {
                val parent = path.substringBeforeLast('/', "")
                if (parent.isNotEmpty() && parent !in folders) MockResponse().setResponseCode(409)
                else { files[path] = request.body.readUtf8(); MockResponse().setResponseCode(201) }
            }
            "DELETE" -> if (files.remove(path) != null) MockResponse().setResponseCode(204) else MockResponse().setResponseCode(404)
            "PROPFIND" -> {
                if (path.isNotEmpty() && path !in folders) return MockResponse().setResponseCode(404)
                val kids = files.filterKeys { it.substringBeforeLast('/', "") == path }
                val xml = buildString {
                    append("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
                    append("""<d:response><d:href>${base}${java.net.URLEncoder.encode(path, "UTF-8").replace("%2F", "/").replace("+", "%20")}/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                    if (request.getHeader("Depth") == "1") for ((name, body) in kids) {
                        val leaf = name.substringAfterLast('/')
                        append("""<d:response><d:href>${base}${java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")}/${java.net.URLEncoder.encode(leaf, "UTF-8").replace("+", "%20")}</d:href><d:propstat><d:prop><d:getcontentlength>${body.toByteArray().size}</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""")
                    }
                    append("</d:multistatus>")
                }
                MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(xml)
            }
            else -> MockResponse().setResponseCode(405)
        }
    }
}

class SyncTest {
    private lateinit var server: MockWebServer
    private lateinit var dav: FakeDav
    private lateinit var account: Account

    @Before
    fun up() {
        dav = FakeDav("ann", "app-pass-1")
        server = MockWebServer().apply { dispatcher = dav; start() }
        account = Account(server.url("/").toString().trimEnd('/'), "ann", "app-pass-1")
        dav.folders += ""
    }

    @After
    fun down() = server.shutdown()

    private fun dream(stamp: String, text: String?) =
        Dream(stamp, LocalDateTime.parse(stamp, Dream.STAMP), 30, text)

    private fun render(d: Dream) = "# night\n\n${d.text}\n"

    private fun run(folder: String, dreams: List<Dream>, sent: MutableMap<String, Sent>) {
        sync(DreamFolder(account, folder), dreams, sent.toMap(), ::render) { stamp, was ->
            if (was == null) sent.remove(stamp) else sent[stamp] = was
        }
    }

    // Planning -----------------------------------------------------------------------------------

    @Test
    fun `a dream with words is planned under its time, one without words waits`() {
        val steps = plan(listOf(dream("20261005-071204", "I flew."), dream("20261005-080000", null), dream("20261005-081500", "  ")), emptyMap(), ::render)
        assertEquals(listOf<Step>(Step.Put("20261005-071204", "2026-10-05 0712.md", "# night\n\nI flew.\n")), steps)
    }

    @Test
    fun `two dreams in the same minute get different names`() {
        val steps = plan(listOf(dream("20261005-071204", "a"), dream("20261005-071230", "b")), emptyMap(), ::render)
        assertEquals(listOf("2026-10-05 0712.md", "2026-10-05 0712 (2).md"), steps.map { (it as Step.Put).name })
    }

    @Test
    fun `a sent dream is left alone, changed words are sent again under the same name, a gone dream is removed`() {
        val d = dream("20261005-071204", "I flew.")
        val sent = mapOf(
            d.stamp to Sent("2026-10-05 0712.md", fingerprint(render(d))),
            "20261001-010101" to Sent("2026-10-01 0101.md", "x"),
        )
        assertEquals(listOf<Step>(Step.Remove("20261001-010101", "2026-10-01 0101.md")), plan(listOf(d), sent, ::render))
        val changed = d.copy(text = "I flew far.")
        val steps = plan(listOf(changed), mapOf(d.stamp to sent.getValue(d.stamp)), ::render)
        assertEquals(listOf<Step>(Step.Put(d.stamp, "2026-10-05 0712.md", render(changed))), steps)
    }

    // Against the in-memory server ----------------------------------------------------------------

    @Test
    fun `folders are made and only the words are put`() {
        val sent = mutableMapOf<String, Sent>()
        run("Dream Log/2026", listOf(dream("20261005-071204", "I flew over a field.")), sent)
        assertTrue("Dream Log" in dav.folders && "Dream Log/2026" in dav.folders)
        assertEquals(mapOf("Dream Log/2026/2026-10-05 0712.md" to "# night\n\nI flew over a field.\n"), dav.files)
        assertEquals(setOf("20261005-071204"), sent.keys)
        // Nothing but text was ever sent.
        assertTrue(dav.log.none { it.contains(".wav") })
    }

    @Test
    fun `a second run with nothing new makes no request at all`() {
        val sent = mutableMapOf<String, Sent>()
        val dreams = listOf(dream("20261005-071204", "I flew."))
        run("Dream Log", dreams, sent)
        val before = dav.log.size
        run("Dream Log", dreams, sent)
        assertEquals(before, dav.log.size)
    }

    @Test
    fun `a deleted dream's copy is deleted, and a copy already gone is no error`() {
        val sent = mutableMapOf<String, Sent>()
        val one = dream("20261005-071204", "I flew.")
        val two = dream("20261006-031500", "I swam.")
        run("Dream Log", listOf(one, two), sent)
        assertEquals(2, dav.files.size)
        run("Dream Log", listOf(two), sent)
        assertEquals(setOf("Dream Log/2026-10-06 0315.md"), dav.files.keys)
        dav.files.clear()
        run("Dream Log", emptyList(), sent)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a dream with a name in an odd character set is put under an encoded address`() {
        val sent = mutableMapOf<String, Sent>()
        run("Dreams & more/été", listOf(dream("20261005-071204", "x")), sent)
        assertTrue("Dreams & more/été" in dav.folders)
        assertEquals(1, dav.files.size)
    }

    @Test
    fun `a wrong app password is refused and nothing is recorded`() {
        val sent = mutableMapOf<String, Sent>()
        account = account.copy(password = "wrong")
        try {
            run("Dream Log", listOf(dream("20261005-071204", "I flew.")), sent)
            fail("expected a refusal")
        } catch (e: Refused) {
            // as it should
        }
        assertTrue(sent.isEmpty())
        assertTrue(dav.files.isEmpty())
    }

    @Test
    fun `a server that is not there is Unreachable`() {
        val dead = Account("http://127.0.0.1:1", "ann", "x")
        try {
            DreamFolder(dead, "Dream Log").probe()
            fail("expected unreachable")
        } catch (e: Unreachable) {
            // as it should
        }
    }

    @Test
    fun `a failure partway keeps what was sent`() {
        val sent = mutableMapOf<String, Sent>()
        val first = dream("20261005-071204", "I flew.")
        run("Dream Log", listOf(first), sent)
        // The server stops taking new files after this point.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        try {
            run("Dream Log", listOf(first, dream("20261006-031500", "I swam.")), sent)
            fail("expected an error")
        } catch (e: java.io.IOException) {
            // as it should
        }
        assertEquals(setOf(first.stamp), sent.keys)
    }

    @Test
    fun `probe passes for a good sign-in and the listing sees what is there`() {
        val folder = DreamFolder(account, "Dream Log")
        folder.probe()
        assertNull(folder.list())
        folder.makeFolders()
        folder.put("a b.md", "hello")
        assertEquals(mapOf("a b.md" to 5L), folder.list())
    }

    // Signing in ----------------------------------------------------------------------------------

    @Test
    fun `login flow v2 start and poll`() {
        server.dispatcher = object : Dispatcher() {
            var polls = 0
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/index.php/login/v2" -> MockResponse().setBody(
                    """{"poll":{"token":"tok","endpoint":"${server.url("/login/v2/poll")}"},"login":"${server.url("/login/v2/flow/tok")}"}""",
                )
                "/login/v2/poll" ->
                    if (polls++ == 0) MockResponse().setResponseCode(404)
                    else MockResponse().setBody("""{"server":"${server.url("/").toString().trimEnd('/')}","loginName":"ann","appPassword":"p4ss"}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        val started = LoginFlow.start(account.server)
        assertEquals("tok", started.token)
        assertNull(LoginFlow.poll(started))
        val got = LoginFlow.poll(started)!!
        assertEquals("ann", got.user)
        assertEquals("p4ss", got.password)
    }

    @Test
    fun `addresses are made https and plain http is caught`() {
        assertEquals("https://cloud.example.org", LoginFlow.normalise(" cloud.example.org/ "))
        assertTrue(LoginFlow.isPlain("http://cloud.example.org"))
        assertFalse(LoginFlow.isPlain("https://cloud.example.org"))
        assertFalse(LoginFlow.isPlain("cloud.example.org"))
    }

    @Test
    fun `a listing is read with folders marked and hrefs decoded`() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/remote.php/dav/files/ann/Dream%20Log/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/remote.php/dav/files/ann/Dream%20Log/a%20b.md</d:href><d:propstat><d:prop><d:getcontentlength>12</d:getcontentlength><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>"""
        val entries = parseMultistatus(xml.byteInputStream())
        assertEquals(listOf(Entry("/remote.php/dav/files/ann/Dream Log/", -1, true), Entry("/remote.php/dav/files/ann/Dream Log/a b.md", 12, false)), entries)
    }
}

class SyncGuardTest {
    private fun dream(stamp: String, text: String?) =
        Dream(stamp, LocalDateTime.parse(stamp, Dream.STAMP), 30, text)

    private val sent = mapOf(
        "20261001-010101" to Sent("2026-10-01 0101.md", "x"),
        "20261002-010101" to Sent("2026-10-02 0101.md", "y"),
        "20261003-010101" to Sent("2026-10-03 0101.md", "z"),
    )

    @Test
    fun `an empty log never plans to delete several copies`() {
        assertTrue(plan(emptyList(), sent) { "" }.none { it is Step.Remove })
        assertFalse(removalsAreSafe(emptyList(), sent))
    }

    @Test
    fun `the last copy still goes when its one dream is deleted`() {
        val one = mapOf("20261001-010101" to Sent("2026-10-01 0101.md", "x"))
        assertEquals(listOf<Step>(Step.Remove("20261001-010101", "2026-10-01 0101.md")), plan(emptyList(), one) { "" })
    }

    @Test
    fun `with some dreams still listed a gone one is removed`() {
        val kept = dream("20261001-010101", "a")
        val steps = plan(listOf(kept), sent) { "a" }
        assertEquals(setOf("20261002-010101", "20261003-010101"), steps.filterIsInstance<Step.Remove>().map { it.stamp }.toSet())
    }

    @Test
    fun `the login page must be https on the typed host`() {
        assertTrue(LoginFlow.acceptableLoginUrl("https://cloud.example/index.php/login/v2/flow/abc", "cloud.example"))
        assertTrue(LoginFlow.acceptableLoginUrl("https://Cloud.Example/login", "https://cloud.example/"))
        assertFalse(LoginFlow.acceptableLoginUrl("http://cloud.example/login", "cloud.example"))
        assertFalse(LoginFlow.acceptableLoginUrl("https://evil.example/login", "cloud.example"))
        assertFalse(LoginFlow.acceptableLoginUrl("intent://scan/#Intent;scheme=zxing;end", "cloud.example"))
        assertFalse(LoginFlow.acceptableLoginUrl("javascript:alert(1)", "cloud.example"))
        assertFalse(LoginFlow.acceptableLoginUrl("", "cloud.example"))
    }

    @Test
    fun `signing in by hand says what is missing`() {
        assertEquals(Problem.NEEDS_ADDRESS, handProblem("", "ann", "pw"))
        assertEquals(Problem.PLAIN_HTTP, handProblem("http://cloud.example", "ann", "pw"))
        assertEquals(Problem.NEEDS_USER, handProblem("cloud.example", "", "pw"))
        assertEquals(null, handProblem("cloud.example", "ann", "pw"))
    }
}
