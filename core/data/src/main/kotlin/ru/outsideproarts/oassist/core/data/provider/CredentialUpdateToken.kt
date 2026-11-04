package ru.outsideproarts.oassist.core.data.provider

/** One save owner's optimistic chain; only its completed credential writes advance it. */
class CredentialUpdateToken(val providerId: String, revision: String?, expectedExists: Boolean? = null) {
    var revision: String? = revision
        internal set
    /** Own terminal revision for a fresh validation attempt, including after failure. */
    var retryRevision: String? = revision
        internal set
    var expectedExists: Boolean? = expectedExists
        internal set
}

class CredentialRevisionMismatchException : IllegalStateException("Provider credentials changed during validation")
