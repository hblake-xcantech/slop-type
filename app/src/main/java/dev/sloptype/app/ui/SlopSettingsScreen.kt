package dev.sloptype.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sloptype.app.SlopPaths
import dev.sloptype.app.SlopRuntime
import dev.sloptype.app.SlopSecrets
import dev.sloptype.app.ime.SlopPrivacy
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import java.io.File
import androidx.core.content.edit

/**
 * The Slop Type page. No thresholds, no sliders: status, secrets, privacy, and maintenance actions.
 */
@Composable
fun SlopSettingsScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    SearchSettingsScreen(onClickBack = onClickBack, title = "Slop Type", settings = emptyList()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            StatusSection(ctx)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            PrivacySection(ctx)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SecretsSection(ctx)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ActionsSection(ctx)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusSection(ctx: Context) {
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { status = buildStatus(ctx) }
    Text("Status", style = MaterialTheme.typography.titleMedium)
    Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

private fun buildStatus(ctx: Context): String {
    val e = SlopRuntime.engineOrNull()
    val sb = StringBuilder()
    sb.append("Engine: ").append(if (e == null) "loading…" else "ready, personal lexicon ${e.personal.size()} words, policy updates ${e.policy.updates}").append('\n')
    val spend = e?.router?.spend
    val cap = e?.router?.capUsd ?: 0.0
    sb.append("Tier 2: ").append(e?.router?.routerConfig?.tier2Provider ?: "-").append("  spend this month $").append("%.3f".format(spend?.usd ?: 0.0)).append(" / cap $").append("%.2f".format(cap)).append('\n')
    sb.append("Tier 3: ").append(if (e?.tier3?.available == true) "signed in" else "not signed in").append('\n')
    sb.append("Neural LM: ").append(if (e?.neural != null) "loaded" else if (dev.sloptype.llamabridge.ModelStore.modelFile(ctx).exists()) "downloaded, not loaded" else "not downloaded").append('\n')
    val st = File(SlopPaths.state(ctx), "daemon-status.json")
    sb.append("Daemon: ").append(if (st.exists()) st.readText().take(400) else "never ran").append('\n')
    val c = SlopRuntime.logger.counters().snapshot()
    val today = c.keys.maxOrNull()
    if (today != null) sb.append("Today: ").append(c[today]).append('\n')
    return sb.toString()
}

@Composable
private fun PrivacySection(ctx: Context) {
    val prefs = ctx.prefs()
    var logging by remember { mutableStateOf(SlopPrivacy.loggingEnabled(ctx)) }
    var excluded by remember { mutableStateOf(prefs.getString(SlopPrivacy.PREF_EXCLUDED_APPS, null) ?: SlopPrivacy.DEFAULT_EXCLUDED.joinToString(", ")) }
    Text("Privacy", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("Log typing data (encrypted before upload)", Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = logging, onCheckedChange = { logging = it; prefs.edit { putBoolean(SlopPrivacy.PREF_LOGGING_ENABLED, it) } })
    }
    Text("Password fields and apps matching these patterns are never logged, learned from, or sent anywhere:", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = excluded, onValueChange = { excluded = it; prefs.edit { putString(SlopPrivacy.PREF_EXCLUDED_APPS, it) } },
        label = { Text("Excluded app patterns (comma separated)") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
}

@Composable
private fun SecretsSection(ctx: Context) {
    var s by remember { mutableStateOf(SlopSecrets.load(ctx)) }
    Text("Secrets (stored only on this phone)", style = MaterialTheme.typography.titleMedium)
    SecretField("GitHub token (slop-type-data read/write)", s.githubToken) { s = s.copy(githubToken = it); SlopSecrets.save(ctx, s) }
    SecretField("OpenAI API key (tier 2)", s.openaiApiKey) { s = s.copy(openaiApiKey = it); SlopSecrets.save(ctx, s) }
    SecretField("TypeSafe Jev API key (tier 2)", s.jevApiKey) { s = s.copy(jevApiKey = it); SlopSecrets.save(ctx, s) }
    Row(Modifier.padding(top = 8.dp)) {
        Button(onClick = { ctx.startActivity(Intent(ctx, dev.sloptype.app.remote.ChatGptSignInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
            Text(if (s.chatgptOauth != null) "ChatGPT: signed in (re-sign)" else "Sign in with ChatGPT (tier 3)")
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onSave: (String) -> Unit) {
    var v by remember(label) { mutableStateOf(value) }
    OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    if (v != value) OutlinedButton(onClick = { onSave(v) }) { Text("Save") }
}

@Composable
private fun ActionsSection(ctx: Context) {
    Text("Maintenance", style = MaterialTheme.typography.titleMedium)
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    OutlinedButton(onClick = { dev.sloptype.app.daemon.Daemon.runNow(ctx); toast("Daemon queued") }, Modifier.fillMaxWidth()) { Text("Sync now (upload logs, pull configs, check updates)") }
    OutlinedButton(onClick = { dev.sloptype.app.remote.Benchmark.runAsync(ctx); toast("Benchmark started, see bench/latest.json") }, Modifier.fillMaxWidth()) { Text("Run tier benchmark") }
    OutlinedButton(onClick = { dev.sloptype.app.remote.NeuralLoader.ensureModelAsync(ctx); toast("Model download queued (Wi-Fi only)") }, Modifier.fillMaxWidth()) { Text("Download on-device LM (145 MB)") }
    OutlinedButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, Modifier.fillMaxWidth()) { Text("Enable screen context (accessibility)") }
    OutlinedButton(onClick = { SlopRuntime.saveState(ctx); toast("Saved") }, Modifier.fillMaxWidth()) { Text("Save engine state now") }
}
