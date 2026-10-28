package com.wanderwildwood.yumecho.cloud

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The dreams folder in a Nextcloud, over WebDAV: `remote.php/dav/files/<user>/<folder>/`.
 *
 * Five calls and nothing else: PROPFIND to check a sign-in and to see what the folder holds,
 * MKCOL to make a folder that is missing, PUT to send a dream's words, and DELETE to remove the
 * copy of a dream that was deleted on the phone. Only text goes up, never a recording.
 */
class DreamFolder(
    private val account: Account,
    folder: String,
    private val client: OkHttpClient = http,
) {
    private val home = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user) + "/"
    private val root = folder.trim('/', ' ')

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
        .header("User-Agent", AGENT)

    /** The address of the folder, ending in a slash. */
    fun folderUrl(): String = home + if (root.isEmpty()) "" else encodePath(root) + "/"

    /** The files directly in the folder, each by name with its size; null when it is not there yet. */
    fun list(): Map<String, Long>? {
        val url = folderUrl()
        val entries = try {
            propfind(url, 1)
        } catch (_: Missing) {
            return null
        }
        val self = decodeHref(url)
        return entries.asSequence()
            .filter { !it.isFolder && it.path.startsWith(self) }
            .map { it.path.removePrefix(self) to it.size }
            .filter { (name, _) -> name.isNotEmpty() && !name.contains('/') }
            .toMap()
    }

    /** The folder and every folder above it, up to the user's files, each made where it is missing. */
    fun makeFolders() {
        var sofar = home
        for (part in root.split('/').filter { it.isNotEmpty() }) {
            sofar += encodePath(part) + "/"
            call(request(sofar).method("MKCOL", null).build()) { response ->
                // 405 is "already there".
                if (response.code != 201 && response.code != 405) check(response)
            }
        }
    }

    /** One dream's words as [name]; an earlier copy of that name is replaced. */
    fun put(name: String, text: String) {
        val body = text.toRequestBody("text/markdown; charset=utf-8".toMediaType())
        call(request(folderUrl() + encodePath(name)).put(body).build()) { response -> check(response) }
    }

    /** The copy called [name]; already gone is fine. */
    fun delete(name: String) {
        call(request(folderUrl() + encodePath(name)).delete().build()) { response ->
            if (response.code != 404) check(response)
        }
    }

    /**
     * Ask the server once, before an account is kept: that these credentials open the user's
     * files. [Refused] or [Unreachable] when not.
     */
    fun probe() {
        try {
            propfind(home, 0)
        } catch (_: Missing) {
            throw Refused("no files for ${account.user}")
        }
    }

    private fun propfind(url: String, depth: Int): List<Entry> {
        val request = request(url).method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).header("Depth", depth.toString()).build()
        return call(request) { response ->
            if (response.code == 404) throw Missing()
            check(response)
            if (response.code != 207) throw IOException("${response.code} for PROPFIND")
            response.body!!.byteStream().use(::parseMultistatus)
        }
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "no answer")
        }
        return response.use(read)
    }

    private fun check(response: Response) {
        when {
            response.isSuccessful -> Unit
            response.code == 401 || response.code == 403 -> throw Refused("${response.code}")
            else -> throw IOException("${response.code} for ${response.request.method}")
        }
    }

    /** Nothing at that path. Inside this file only. */
    private class Missing : IOException("not there")
}

/** One entry of a WebDAV listing. [path] is decoded; a folder's ends in "/". */
data class Entry(val path: String, val size: Long, val isFolder: Boolean)

/**
 * The answer to a PROPFIND: one `<d:response>` per file or folder, with the properties asked
 * for that the server has. Read with the platform's own XML parser, which works the same off
 * the phone, where the tests run.
 */
fun parseMultistatus(body: InputStream): List<Entry> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // A server's answer is not trusted to name outside files for the parser to fetch.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }
    val document = factory.newDocumentBuilder().parse(body)
    val responses = document.getElementsByTagNameNS(DAV, "response")
    val out = mutableListOf<Entry>()
    for (i in 0 until responses.length) {
        val response = responses.item(i) as Element
        val href = response.first(DAV, "href")?.textContent?.trim() ?: continue
        var size = -1L
        var folder = false
        val propstats = response.getElementsByTagNameNS(DAV, "propstat")
        for (j in 0 until propstats.length) {
            val propstat = propstats.item(j) as Element
            if (propstat.first(DAV, "status")?.textContent?.contains(" 200 ") != true) continue
            propstat.first(DAV, "getcontentlength")?.textContent?.trim()?.toLongOrNull()?.let { size = it }
            if (propstat.first(DAV, "resourcetype")?.let { it.getElementsByTagNameNS(DAV, "collection").length > 0 } == true) {
                folder = true
            }
        }
        var path = decodeHref(href)
        if (folder && !path.endsWith("/")) path += "/"
        out += Entry(path, size, folder)
    }
    return out
}

/** The path an href names, decoded; a full URL or only a path both come out as the path. */
fun decodeHref(href: String): String {
    val raw = if (href.startsWith("http://") || href.startsWith("https://")) URI(href).rawPath else href
    return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace(Regex("/{2,}"), "/")
}

/** Each segment of a path percent-encoded for a URL, the slashes kept. */
fun encodePath(path: String): String =
    path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

private const val DAV = "DAV:"
private val XML = "application/xml; charset=utf-8".toMediaType()

private const val PROPFIND_BODY = """<?xml version="1.0"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"""

private fun Element.first(ns: String, name: String): Element? =
    getElementsByTagNameNS(ns, name).let { if (it.length > 0) it.item(0) as Element else null }
