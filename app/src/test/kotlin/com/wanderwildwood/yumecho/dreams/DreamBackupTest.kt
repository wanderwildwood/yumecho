package com.wanderwildwood.yumecho.dreams

import com.wanderwildwood.yumecho.dreams.DreamBackup.BackupException.Kind
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DreamBackupTest {

    private lateinit var root: File
    private lateinit var source: File
    private lateinit var target: File
    private lateinit var scratch: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("yumecho-test").toFile()
        source = File(root, "source").apply { mkdirs() }
        target = File(root, "target").apply { mkdirs() }
        scratch = File(root, "scratch")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun dream(stamp: String, text: String?, seconds: Int = 2) =
        Dream(stamp, LocalDateTime.parse(stamp, Dream.STAMP), seconds, text)

    private fun wav(seconds: Int, seed: Int): ByteArray {
        val data = ByteArray(seconds * Wav.RATE * 2) { (it * 31 + seed).toByte() }
        return Wav.header(data.size.toLong()) + data
    }

    private val dreams = listOf(
        dream("20260925-020000", "A heron spoke to Ada Whitlock. \"It left.\"\nThen the sea.", 1),
        dream("20260926-031204", null, 2),
        dream("20260926-050000", "", 1),
    )
    private val audio = dreams.mapIndexed { i, d -> d.stamp to wav(d.seconds, i) }.toMap()

    private fun backup(list: List<Dream> = dreams): ByteArray {
        val out = ByteArrayOutputStream()
        DreamBackup.write(out, list, { ByteArrayInputStream(audio.getValue(it.stamp)) }, Instant.parse("2026-11-02T10:00:00Z"))
        return out.toByteArray()
    }

    private fun zip(manifest: String?, vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (manifest != null) { z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry() }
            for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun refused(bytes: ByteArray, kind: Kind) {
        try {
            DreamBackup.stage(ByteArrayInputStream(bytes), scratch)
            fail("should have been refused")
        } catch (e: DreamBackup.BackupException) {
            assertEquals(kind, e.kind)
        }
        assertFalse("scratch must be emptied", scratch.exists())
        assertEquals(0, target.list()!!.size)
    }

    private fun manifestOf(bytes: ByteArray, edit: (JSONObject) -> Unit): String {
        val first = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).let { it.nextEntry; String(it.readBytes()) }
        return JSONObject(first).also(edit).toString()
    }

    @Test
    fun backupThenRestoreGivesTheSameDreams() {
        val staged = DreamBackup.stage(ByteArrayInputStream(backup()), scratch)
        val r = DreamBackup.commit(staged, target, scratch)
        assertEquals(DreamBackup.Added(3, 0), r)
        assertFalse(scratch.exists())
        for (d in dreams) {
            assertArrayEquals(audio.getValue(d.stamp), File(target, "${d.stamp}.wav").readBytes())
            val txt = File(target, "${d.stamp}.txt")
            if (d.text == null) assertFalse(txt.exists()) else assertEquals(d.text, txt.readText())
        }
        assertEquals(5, target.list()!!.size) // three recordings, two with words
    }

    @Test
    fun anEmptyLogRoundTrips() {
        val staged = DreamBackup.stage(ByteArrayInputStream(backup(emptyList())), scratch)
        assertTrue(staged.isEmpty())
        assertEquals(DreamBackup.Added(0, 0), DreamBackup.commit(staged, target, scratch))
    }

    @Test
    fun dreamsAlreadyHereAreLeftAsTheyAre() {
        File(target, "20260925-020000.wav").writeBytes(byteArrayOf(1, 2, 3))
        File(target, "20260925-020000.txt").writeText("what is here now")
        val staged = DreamBackup.stage(ByteArrayInputStream(backup()), scratch)
        assertEquals(DreamBackup.Added(2, 1), DreamBackup.commit(staged, target, scratch))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(target, "20260925-020000.wav").readBytes())
        assertEquals("what is here now", File(target, "20260925-020000.txt").readText())
    }

    @Test
    fun theMarkdownCopyIsNotABackup() {
        refused("# Dream Log\n\n## Night of 25 September\n".toByteArray(), Kind.NOT_A_BACKUP)
    }

    @Test
    fun otherZipsAndNonsenseAreNotABackup() {
        refused(ByteArray(0), Kind.NOT_A_BACKUP)
        refused("hello".toByteArray(), Kind.NOT_A_BACKUP)
        refused(zip(null, "notes.txt" to "x".toByteArray()), Kind.NOT_A_BACKUP)
        refused(zip("{\"format\":\"fukuyaku-backup\",\"version\":1}"), Kind.NOT_A_BACKUP)
        refused(zip("not json"), Kind.NOT_A_BACKUP)
    }

    @Test
    fun aNewerVersionIsNamedAsNewer() {
        refused(zip(manifestOf(backup()) { it.put("version", 2) }), Kind.NEWER)
    }

    @Test
    fun aMissingRecordingMakesTheWholeFileDamaged() {
        val m = manifestOf(backup()) { }
        refused(zip(m, "audio/20260925-020000.wav" to audio.getValue("20260925-020000")), Kind.DAMAGED)
    }

    @Test
    fun anExtraOrMisnamedPartIsDamaged() {
        val m = manifestOf(backup()) { }
        val all = audio.map { "audio/${it.key}.wav" to it.value }.toTypedArray()
        refused(zip(m, *all, "audio/20270101-000000.wav" to wav(1, 9)), Kind.DAMAGED)
        refused(zip(m, *all, "../evil.txt" to byteArrayOf(1)), Kind.DAMAGED)
    }

    @Test
    fun aBadStampOrRepeatedDreamInTheManifestIsDamaged() {
        val all = audio.map { "audio/${it.key}.wav" to it.value }.toTypedArray()
        refused(zip(manifestOf(backup()) { it.getJSONArray("dreams").getJSONObject(0).put("stamp", "../../x") }, *all), Kind.DAMAGED)
        refused(zip(manifestOf(backup()) { it.getJSONArray("dreams").getJSONObject(0).put("stamp", "20261399-999999") }, *all), Kind.DAMAGED)
        refused(zip(manifestOf(backup()) { it.getJSONArray("dreams").getJSONObject(1).put("stamp", "20260925-020000") }, *all), Kind.DAMAGED)
    }

    @Test
    fun aRecordingThatIsNotOurSoundFormatIsDamaged() {
        val m = manifestOf(backup()) { }
        val bad = audio.map { (k, v) -> "audio/$k.wav" to (if (k == "20260926-050000") v.copyOf(30) else v) }.toTypedArray()
        refused(zip(m, *bad), Kind.DAMAGED)
        val notWav = audio.map { (k, v) -> "audio/$k.wav" to (if (k == "20260926-050000") ByteArray(v.size) else v) }.toTypedArray()
        refused(zip(m, *notWav), Kind.DAMAGED)
    }

    @Test
    fun aCutOffOrCorruptedFileChangesNothing() {
        val bytes = backup()
        refused(bytes.copyOf(bytes.size * 2 / 3), Kind.DAMAGED)
        // Flip a byte inside the middle of the compressed data: the CRC no longer matches.
        val flipped = bytes.copyOf().also { it[bytes.size / 2] = (it[bytes.size / 2].toInt() xor 0x55).toByte() }
        try {
            DreamBackup.stage(ByteArrayInputStream(flipped), scratch)
            fail("a corrupted file must be refused")
        } catch (e: DreamBackup.BackupException) {
            assertFalse(scratch.exists())
        }
        assertEquals(0, target.list()!!.size)
    }
}
