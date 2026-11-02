package com.davideloconte.screenlet

import android.content.Context

/** Persistent application configuration stored in shared preferences. */
object Options {
    private const val PREFS = "options"

    /** Checks whether the companion service is configured to remain persistently ready. */
    fun alwaysReady(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("always", true)

    fun setAlwaysReady(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("always", on).apply()

}

/** Persistent registry of paired host devices keyed by certificate fingerprint. */
object Paired {
    private const val PREFS = "paired"

    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapValues { it.value as String }

    fun contains(context: Context, fingerprint: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(fingerprint)

    fun add(context: Context, fingerprint: String, name: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(fingerprint, name).apply()

    fun forget(context: Context, fingerprint: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(fingerprint).apply()
}
