package ru.outsideproarts.oassist.core.data.provider

/** One save owner's optimistic chain; only its completed credential writes advance it. */
class CredentialUpdateToken(val providerId: String, revision: String?) {
    var revision: String? = revision
        internal set
}

class CredentialRevisionMismatchException : IllegalStateException("Provider credentials changed during validation")
