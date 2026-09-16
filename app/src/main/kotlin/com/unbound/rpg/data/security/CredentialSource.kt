package com.unbound.rpg.data.security

/**
 * The two things an HTTP client needs of a credential, and nothing else.
 *
 * Deliberately this narrow. The real implementation is backed by the Android Keystore and cannot be
 * constructed in a unit test, which for a long time meant the request-building code — the part that
 * decides whether a key ends up in a header or a URL — could not be tested at all. A seam this
 * small adds no indirection worth the name and makes that testable.
 *
 * Note what is *not* here: no way to enumerate, export or log a key. The only accessor returns the
 * plaintext for immediate use in one header, exactly as [SecureCredentialStore] documents.
 */
interface CredentialSource {
    fun hasKey(): Boolean

    /** Use and discard. Never store the result in a field or anything serializable. */
    fun readKey(): String?
}
