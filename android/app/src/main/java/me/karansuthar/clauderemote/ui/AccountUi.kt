package me.karansuthar.clauderemote.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.karansuthar.clauderemote.bridge.BridgeClient
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToInt

// ---------------------------------------------------------------- data (fetched live, never stored)

// @Immutable: compared by equals() so the cards skip recomposition when a poll returns the same data.
@Immutable
data class AccountInfo(
    val loggedIn: Boolean,
    val email: String?,
    val org: String?,
    val plan: String?,
    val method: String?,
)

@Immutable
data class UsageLimit(val name: String, val utilization: Double, val resetsAt: Long?)

@Immutable
data class UsageInfo(val subscription: String?, val available: Boolean, val limits: List<UsageLimit>)

private fun JSONObject.str(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotEmpty() } else null

fun parseAccount(o: JSONObject): AccountInfo = AccountInfo(
    loggedIn = o.optBoolean("loggedIn", false),
    email = o.str("email"),
    org = o.str("org"),
    plan = o.str("plan"),
    method = o.str("method"),
)

private fun parseIsoMillis(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try { Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
    }
}

fun parseUsage(o: JSONObject): UsageInfo {
    val arr = o.optJSONArray("limits")
    val limits = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
        val l = arr.optJSONObject(i) ?: return@mapNotNull null
        val u = l.optDouble("utilization", 0.0).let { if (it.isNaN()) 0.0 else it }
        UsageLimit(
            name = l.str("name") ?: "Limit",
            utilization = u,
            resetsAt = parseIsoMillis(l.str("resetsAt")),
        )
    }
    return UsageInfo(
        subscription = o.str("subscription"),
        available = o.optBoolean("available", false),
        limits = limits,
    )
}

/** True when a bridge error looks like Claude Code isn't signed in. */
fun isAuthError(message: String?): Boolean {
    val m = message?.lowercase() ?: return false
    return "auth" in m || "login" in m || "401" in m
}

/** A message fit to show the user for a failed request; [fallback] when there's nothing readable. */
fun Throwable.humanMessage(fallback: String): String {
    if (this is org.json.JSONException) return "The computer sent a reply this app doesn't understand. Update the bridge and the app."
    val m = message?.trim().orEmpty()
    if (m.isEmpty()) return fallback
    // Low-level socket errors aren't helpful on their own.
    if (this is java.net.SocketTimeoutException || this is java.net.UnknownHostException || this is java.net.ConnectException) {
        return "Can't reach the computer. Is Tailscale on and the bridge running?"
    }
    return m
}

private fun String.capitalized(): String = replaceFirstChar { it.titlecase() }

private fun prettyLimitName(name: String): String = name.replace('_', ' ').trim().capitalized()

// ---------------------------------------------------------------- cards

@Composable
fun AccountCard(
    account: AccountInfo?,
    needsSignIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!needsSignIn && account?.loggedIn != true) return
    OutlinedCard(modifier.fillMaxWidth()) {
        if (needsSignIn || account == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Claude Code isn't signed in on this computer", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onSignIn) { Text("Sign in") }
            }
        } else {
            Row(
                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        account.email ?: "Signed in",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val details = listOfNotNull(account.org, account.plan?.capitalized())
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Sign out lives in an overflow menu so it can't be tapped by accident.
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, "Account options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                            onClick = {
                                menu = false
                                onSignOut()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UsageCard(usage: UsageInfo, modifier: Modifier = Modifier) {
    if (!usage.available || usage.limits.isEmpty()) return
    val now = System.currentTimeMillis()
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Usage", style = MaterialTheme.typography.titleSmall)
            usage.limits.forEach { limit ->
                val pct = limit.utilization.coerceIn(0.0, 100.0)
                val high = limit.utilization >= 90
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(prettyLimitName(limit.name), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat() },
                        color = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val resets = limit.resetsAt?.let {
                        " · resets " + DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS)
                            .toString()
                            .replaceFirstChar { c -> c.lowercaseChar() }
                    } ?: ""
                    Text(
                        "${limit.utilization.roundToInt()}% used$resets",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- sign-in dialog

@Composable
fun SignInDialog(client: BridgeClient, onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    var console by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun cancel() {
        client.post("login_cancel")
        onDismiss()
    }

    fun start() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val reply = client.request("login_start", JSONObject().put("console", console))
                val link = reply.optString("url").trim()
                if (link.isEmpty()) throw Exception("The computer didn't return a sign-in link.")
                url = link
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.humanMessage("Couldn't start sign-in.")
            } finally {
                busy = false
            }
        }
    }

    fun finish() {
        val c = code.trim()
        if (busy || c.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            try {
                val reply = client.request("login_code", JSONObject().put("code", c), timeoutMs = 90_000L)
                val acc = reply.optJSONObject("account")?.let(::parseAccount)
                if (reply.has("ok") && !reply.optBoolean("ok", true) && acc?.loggedIn != true) {
                    throw Exception("Sign-in didn't complete. Check the code and try again.")
                }
                onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Finishing can take longer than a request's timeout: if the reply didn't come
                // in time, check the account for a while before giving up.
                val timedOut = e.message?.contains("in time") == true
                if (timedOut && waitForLogin(client)) {
                    onSignedIn()
                } else {
                    error = e.humanMessage("Couldn't finish sign-in.")
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) cancel() },
        title = { Text("Sign in to Claude Code") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val link = url
                if (link == null) {
                    Text("Sign in with", style = MaterialTheme.typography.titleSmall)
                    RadioRow(selected = !console, title = "Claude subscription (Pro/Max/Team)", enabled = !busy) { console = false }
                    RadioRow(selected = console, title = "Anthropic Console (API billing)", enabled = !busy) { console = true }
                } else {
                    Text("1. Open this link and sign in", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(
                            link,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Sign-in link", link))
                        }) { Text("Copy link") }
                        OutlinedButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                            } catch (e: ActivityNotFoundException) {
                                error = "No app can open this link. Copy it into a browser instead."
                            }
                        }) { Text("Open") }
                    }
                    Text(
                        "2. Paste the code shown after signing in",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; error = null },
                        label = { Text("Code") },
                        singleLine = true,
                        enabled = !busy,
                        trailingIcon = {
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    val text = clipboard?.primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                        ?.toString()
                                        ?.trim()
                                    if (!text.isNullOrEmpty()) {
                                        code = text
                                        error = null
                                    }
                                },
                            ) { Icon(Icons.Default.ContentPaste, "Paste") }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            if (link == null) "Starting sign-in on the computer…" else "Finishing sign-in, this can take up to a minute…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            if (url == null) {
                TextButton(onClick = ::start, enabled = !busy) { Text("Continue") }
            } else {
                TextButton(onClick = ::finish, enabled = !busy && code.isNotBlank()) { Text("Finish sign-in") }
            }
        },
        dismissButton = { TextButton(onClick = ::cancel) { Text("Cancel") } },
    )
}

/** Polls `account` for up to about a minute; true once Claude Code reports being signed in. */
private suspend fun waitForLogin(client: BridgeClient): Boolean {
    repeat(15) {
        try {
            if (parseAccount(client.request("account")).loggedIn) return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep trying
        }
        delay(4_000)
    }
    return false
}

@Composable
private fun RadioRow(selected: Boolean, title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(title, Modifier.padding(start = 12.dp))
    }
}
