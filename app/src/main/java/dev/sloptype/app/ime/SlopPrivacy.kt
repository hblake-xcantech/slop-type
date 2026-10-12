package dev.sloptype.app.ime

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.prefs

/** Decides which fields are off-limits for logging and remote tiers. */
object SlopPrivacy {
    const val PREF_EXCLUDED_APPS = "slop_excluded_apps"
    const val PREF_LOGGING_ENABLED = "slop_logging_enabled"

    /** substrings matched against the package name (lower-case) */
    val DEFAULT_EXCLUDED = listOf(
        "bank", "banking", "finance", "financial", "wallet", "pay", "venmo", "paypal", "zelle", "cash.app", "cashapp",
        "chase", "wellsfargo", "bofa", "bankofamerica", "capitalone", "citi", "usbank", "schwab", "fidelity", "vanguard",
        "robinhood", "coinbase", "crypto", "binance", "kraken", "mint", "intuit", "turbotax", "quicken",
        "1password", "onepassword", "bitwarden", "lastpass", "keepass", "dashlane", "nordpass", "protonpass", "passwords",
        "authy", "authenticator", "otp", "yubico", "okta", "duo",
        "com.android.settings", "com.google.android.gms",
    )

    fun excludedPatterns(ctx: Context): List<String> {
        val raw = ctx.prefs().getString(PREF_EXCLUDED_APPS, null) ?: return DEFAULT_EXCLUDED
        return raw.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    fun isExcludedPackage(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return excludedPatterns(ctx).any { p.contains(it) }
    }

    fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return false
    }

    /** true = never log, never send anywhere, never learn */
    fun isSensitive(ctx: Context, editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return true
        if (isPasswordInputType(editorInfo.inputType)) return true
        if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        if (isExcludedPackage(ctx, editorInfo.packageName)) return true
        return false
    }

    fun loggingEnabled(ctx: Context): Boolean = ctx.prefs().getBoolean(PREF_LOGGING_ENABLED, true)
}
