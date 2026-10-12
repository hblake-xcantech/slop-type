package dev.sloptype.agekt

/** Thrown for malformed input, unknown recipients/identities, failed authentication, or unsupported features. */
class AgeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
