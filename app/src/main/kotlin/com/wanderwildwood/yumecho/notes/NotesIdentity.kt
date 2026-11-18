package com.wanderwildwood.yumecho.notes

/** Who Memo is: its package name and the certificate its releases are signed with. */
internal object NotesIdentity {
    const val NOTES = "com.wanderwildwood.oboegaki"

    /** The SHA-256 of the certificate Memo's releases are signed with (lower-case hex). */
    const val NOTES_CERT = "20e289f7dcdd22e11dc4dfdb46ecd1b883615f3350904c7575afc1849632514e"

    /**
     * The debug certificates of the build box and the desk machine. Looked at only by a debug
     * build, so two debug builds can be tried together; a release never answers to them.
     */
    val DEBUG_CERTS = setOf(
        "1e7e9cc0ea37a725d0eaf622187792c425dc0351a3e228ef462f8eaacbb9db06",
        "9ae26e87553000817b4185b15d9047845e87871d8f5204dac77b82183fee248b",
    )

    /**
     * Whether a provider owned by [owner], signed with [certs], is the real Memo: the package
     * name must be Memo's, and every signer must be its release certificate (or, in a debug build
     * only, one of the debug ones).
     */
    fun isNotes(owner: String?, certs: List<String>, debug: Boolean): Boolean {
        if (owner != NOTES || certs.isEmpty()) return false
        return certs.all { it == NOTES_CERT || (debug && it in DEBUG_CERTS) }
    }
}
