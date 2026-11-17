package com.wanderwildwood.yumecho.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesIdentityTest {
    private val notes = NotesIdentity.NOTES
    private val release = NotesIdentity.NOTES_CERT
    private val debugCert = NotesIdentity.DEBUG_CERTS.first()
    private val other = "00".repeat(32)

    @Test
    fun notesSignedWithItsReleaseKeyIsNotes() {
        assertTrue(NotesIdentity.isNotes(notes, listOf(release), debug = false))
    }

    @Test
    fun anotherAppClaimingTheProviderIsNot() {
        assertFalse(NotesIdentity.isNotes("com.example.lookalike", listOf(release), debug = false))
        assertFalse(NotesIdentity.isNotes(null, listOf(release), debug = false))
    }

    @Test
    fun notesNameWithAnotherKeyIsNot() {
        assertFalse(NotesIdentity.isNotes(notes, listOf(other), debug = false))
        assertFalse(NotesIdentity.isNotes(notes, listOf(release, other), debug = false))
        assertFalse(NotesIdentity.isNotes(notes, emptyList(), debug = false))
    }

    @Test
    fun debugKeysOnlyInADebugBuild() {
        assertFalse(NotesIdentity.isNotes(notes, listOf(debugCert), debug = false))
        assertTrue(NotesIdentity.isNotes(notes, listOf(debugCert), debug = true))
    }
}
