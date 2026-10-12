package dev.sloptype.app

import android.content.Context
import java.io.File

/** All Slop Type files live under filesDir/sloptype. Root-only; nothing is world readable. */
object SlopPaths {
    fun root(ctx: Context): File = File(ctx.filesDir, "sloptype").apply { mkdirs() }
    /** current.jsonl (open) + closed <sid>.jsonl waiting to be encrypted */
    fun logs(ctx: Context): File = File(root(ctx), "logs").apply { mkdirs() }
    /** encrypted *.jsonl.age waiting for upload */
    fun outbox(ctx: Context): File = File(root(ctx), "outbox").apply { mkdirs() }
    /** engine.json, router.json, recipients.txt, prompts/ (pulled from slop-type-data) */
    fun configs(ctx: Context): File = File(root(ctx), "configs").apply { mkdirs() }
    /** last-good.apk, pending.apk, install.json */
    fun apk(ctx: Context): File = File(root(ctx), "apk").apply { mkdirs() }
    /** latest.json benchmark results */
    fun bench(ctx: Context): File = File(root(ctx), "bench").apply { mkdirs() }
    /** engine-state.json, personal-lexicon.json, counters.json, daemon-status.json */
    fun state(ctx: Context): File = File(root(ctx), "state").apply { mkdirs() }
    fun models(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun secrets(ctx: Context): File = File(root(ctx), "secrets.json")
}
