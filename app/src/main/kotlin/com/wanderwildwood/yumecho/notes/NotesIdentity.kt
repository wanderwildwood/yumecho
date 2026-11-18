package com.wanderwildwood.yumecho.notes

import com.wanderwildwood.yumecho.ui.HouseApps

/** Who Memo is: its package name and the certificate its releases are signed with (kept in [HouseApps]). */
internal object NotesIdentity {
    const val NOTES = HouseApps.MEMO

    /** The SHA-256 of the certificate Memo's releases are signed with (lower-case hex). */
    val NOTES_CERT: String = HouseApps.RELEASE_CERTS_SHA256.getValue(HouseApps.MEMO)

    /**
     * The debug certificates of the build box and the desk machine. Looked at only by a debug
     * build, so two debug builds can be tried together; a release never answers to them.
     */
    val DEBUG_CERTS: Set<String> = HouseApps.DEBUG_CERTS_SHA256

    /**
     * Whether a provider owned by [owner], signed with [certs], is the real Memo: the package
     * name must be Memo's, and every signer must be its release certificate (or, in a debug build
     * only, one of the debug ones).
     */
    fun isNotes(owner: String?, certs: List<String>, debug: Boolean): Boolean =
        owner == NOTES && HouseApps.trusted(NOTES, certs, debug)
}
