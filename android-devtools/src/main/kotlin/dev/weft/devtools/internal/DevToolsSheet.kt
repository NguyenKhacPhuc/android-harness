package dev.weft.devtools.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.weft.android.WeftRuntime
import dev.weft.harness.observability.AgentTrace
import dev.weft.harness.observability.LlmCallTrace
import dev.weft.harness.observability.ToolCallTrace
import dev.weft.harness.observability.ToolStatus
import dev.weft.harness.observability.TraceFeedback
import dev.weft.harness.observability.TraceStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val label: String) {
    TRACES("Traces"),
    PROMPT("Prompt"),
    TOOLS("Tools"),
    COST("Cost"),
}

/**
 * The actual bottom-sheet UI. Picks the tab to render and dispatches.
 * Apps interact with this only indirectly via [dev.weft.devtools.WeftDevTools].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevToolsSheet(runtime: WeftRuntime, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(Tab.TRACES) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.9f)) {
            Text(
                text = "Weft DevTools",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                when (selectedTab) {
                    Tab.TRACES -> TracesTab(runtime)
                    Tab.PROMPT -> PromptTab(runtime)
                    Tab.TOOLS -> ToolsTab(runtime)
                    Tab.COST -> CostTab(runtime)
                }
            }
        }
    }
}

// ----- Traces tab: list ↔ detail ------------------------------------------

/**
 * Two-level traces view inside the DevTools sheet:
 *
 *   - **List** — newest-first cards, one per turn. Tap to drill in.
 *   - **Detail** — expanded view: meta block, user message, final reply,
 *     LLM calls (with tokens + cache stats + duration), tool calls (with
 *     args / result previews / errors), feedback toggle.
 *
 * Mirrors the structure of the standalone TraceViewerScreen but lives
 * inside the bottom sheet so debug-time inspection doesn't require
 * navigating away from the chat.
 */
@Composable
private fun TracesTab(runtime: WeftRuntime) {
    val traces by runtime.traceStore.traces.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedTraceId by remember { mutableStateOf<String?>(null) }
    val selected = selectedTraceId?.let { id -> traces.firstOrNull { it.id == id } }

    if (selected != null) {
        // Pull this trace's child sub-agent traces from the same store
        // (linked by parentTraceId). Empty for top-level turns without
        // delegation. Passed into TraceDetail so it can render them
        // inline under a "Sub-agents" section, each child tappable to
        // drill in further.
        val children = traces.filter { it.parentTraceId == selected.id }
        TraceDetail(
            trace = selected,
            children = children,
            onBack = { selectedTraceId = null },
            onSetFeedback = { fb ->
                scope.launch { runtime.traceStore.setFeedback(selected.id, fb) }
            },
            onSelectChild = { childId -> selectedTraceId = childId },
        )
        return
    }

    // Top-level list filters out sub-agent traces. Those only appear
    // inline inside their parent's detail view, not as standalone
    // entries — sub-agents aren't user-initiated turns.
    val topLevel = traces.filter { it.parentTraceId == null }
    if (topLevel.isEmpty()) {
        EmptyState("No traces yet — send a message to populate.")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(topLevel.take(50), key = { it.id }) { trace ->
            // Find sub-agent children for badge display on the card.
            val childCount = traces.count { it.parentTraceId == trace.id }
            TraceCard(trace, subAgentCount = childCount, onClick = { selectedTraceId = trace.id })
        }
    }
}

@Composable
private fun TraceCard(
    trace: AgentTrace,
    onClick: () -> Unit,
    subAgentCount: Int = 0,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when (trace.status) {
                TraceStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = trace.userMessage.take(60),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = trace.statusLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = trace.statusColor(),
                )
            }
            // Tight meta line: counts + duration + tokens (when known) +
            // sub-agent badge if this trace delegated to any.
            Text(
                text = listOfNotNull(
                    "${trace.llmCalls.size} LLM",
                    "${trace.toolCalls.size} tool",
                    subAgentCount.takeIf { it > 0 }?.let { "$it sub" },
                    trace.durationMs?.let { "${it}ms" },
                    trace.totalTokens.takeIf { it > 0 }?.let { "$it tok" },
                    trace.feedbackEmoji(),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (trace.toolCalls.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                trace.toolCalls.takeLast(5).forEach { call ->
                    Text(
                        text = "→ ${call.toolName}: ${call.argsPreview.take(80)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (trace.toolCalls.size > 5) {
                    Text(
                        text = "(+${trace.toolCalls.size - 5} more — tap to see all)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceDetail(
    trace: AgentTrace,
    onBack: () -> Unit,
    onSetFeedback: (TraceFeedback) -> Unit,
    children: List<AgentTrace> = emptyList(),
    onSelectChild: (String) -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(modifier = Modifier.weight(1f))
                // For sub-agent detail views, show a small breadcrumb back
                // to the parent's trace. When this is a top-level trace
                // (parentTraceId null) just show the status.
                if (trace.parentTraceId != null) {
                    Text(
                        text = "sub-agent",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                }
                Text(trace.statusLabel(), style = MaterialTheme.typography.titleSmall, color = trace.statusColor())
            }
        }
        item("feedback") { FeedbackRow(trace.feedback, onSetFeedback) }
        item("meta") { TraceMetaBlock(trace) }
        item("user") { LabeledBlock("User", trace.userMessage) }
        trace.finalAssistantMessage?.let { reply ->
            item("assistant") { LabeledBlock("Assistant (final)", reply) }
        }
        trace.errorMessage?.let { err ->
            item("error") { LabeledBlock("Error", err, color = MaterialTheme.colorScheme.error) }
        }
        if (trace.llmCalls.isNotEmpty()) {
            item("llm-header") { SectionHeader("LLM calls (${trace.llmCalls.size})") }
            items(trace.llmCalls, key = { "llm-${it.id}" }) { LlmCallBlock(it) }
        }
        if (trace.toolCalls.isNotEmpty()) {
            item("tool-header") { SectionHeader("Tool calls (${trace.toolCalls.size})") }
            items(trace.toolCalls, key = { "tool-${it.id}" }) { ToolCallBlock(it) }
        }
        if (children.isNotEmpty()) {
            item("sub-header") { SectionHeader("Sub-agents (${children.size})") }
            items(children, key = { "sub-${it.id}" }) { child ->
                SubAgentChildCard(child, onClick = { onSelectChild(child.id) })
            }
        }
    }
}

/**
 * Compact card representing one sub-agent trace inside its parent's
 * detail view. Tap to drill into the sub-agent's own detail page (which
 * itself can have grand-children — depth in DevTools matches whatever
 * the runtime allowed). The runtime caps depth at 1 today, so in
 * practice sub-agent cards are leaves.
 */
@Composable
private fun SubAgentChildCard(child: AgentTrace, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (child.status) {
                TraceStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = child.userMessage.take(80),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = child.statusLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = child.statusColor(),
                )
            }
            Text(
                text = listOfNotNull(
                    "${child.llmCalls.size} LLM",
                    "${child.toolCalls.size} tool",
                    child.durationMs?.let { "${it}ms" },
                    child.totalTokens.takeIf { it > 0 }?.let { "$it tok" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            child.finalAssistantMessage?.takeIf { it.isNotBlank() }?.let { reply ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reply.take(120) + if (reply.length > 120) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FeedbackRow(current: TraceFeedback, onSet: (TraceFeedback) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Helpful?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = {
            onSet(if (current == TraceFeedback.THUMBS_UP) TraceFeedback.NONE else TraceFeedback.THUMBS_UP)
        }) {
            Text(if (current == TraceFeedback.THUMBS_UP) "👍 yes" else "👍")
        }
        TextButton(onClick = {
            onSet(if (current == TraceFeedback.THUMBS_DOWN) TraceFeedback.NONE else TraceFeedback.THUMBS_DOWN)
        }) {
            Text(if (current == TraceFeedback.THUMBS_DOWN) "👎 no" else "👎")
        }
    }
}

@Composable
private fun TraceMetaBlock(trace: AgentTrace) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Conversation: ${trace.conversationId.take(8)}…", style = MaterialTheme.typography.bodySmall)
            Text("Started: ${timeFormat.format(Date(trace.startEpochMs))}", style = MaterialTheme.typography.bodySmall)
            trace.durationMs?.let { Text("Duration: ${it}ms", style = MaterialTheme.typography.bodySmall) }
            if (trace.totalTokens > 0) {
                Text(
                    "Tokens: ${trace.totalInputTokens} in / ${trace.totalOutputTokens} out (${trace.totalTokens} total)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun LabeledBlock(label: String, text: String, color: Color = Color.Unspecified) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun LlmCallBlock(call: LlmCallTrace) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(call.model, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                call.durationMs?.let { Text("${it}ms", style = MaterialTheme.typography.labelSmall) }
            }
            if ((call.totalTokens ?: 0) > 0) {
                Text(
                    "${call.inputTokens ?: 0} in / ${call.outputTokens ?: 0} out (${call.totalTokens} total)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Cache stats — only surface when present. Anthropic reports
            // these when cache_control is in play; OpenAI/local providers
            // either don't or Koog doesn't extract them yet.
            val cacheRead = call.cacheReadTokens ?: 0
            val cacheWrite = call.cacheWriteTokens ?: 0
            if (cacheRead > 0 || cacheWrite > 0) {
                Text(
                    text = "cache: ${cacheRead} read · ${cacheWrite} written",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ToolCallBlock(call: ToolCallTrace) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (call.status) {
                ToolStatus.RUNNING -> MaterialTheme.colorScheme.surfaceVariant
                ToolStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
                ToolStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(call.toolName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.weight(1f))
                call.durationMs?.let { Text("${it}ms", style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "args: ${call.argsPreview}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                    .padding(6.dp),
            )
            call.resultPreview?.let { result ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "result: $result",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                        .padding(6.dp),
                )
            }
            call.errorMessage?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text("error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun AgentTrace.statusLabel(): String = when (status) {
    TraceStatus.FAILED -> "FAILED"
    TraceStatus.COMPLETED -> "OK"
    TraceStatus.RUNNING -> "RUNNING…"
}

@Composable
private fun AgentTrace.statusColor() = when (status) {
    TraceStatus.FAILED -> MaterialTheme.colorScheme.error
    TraceStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    TraceStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun AgentTrace.feedbackEmoji(): String? = when (feedback) {
    TraceFeedback.THUMBS_UP -> "👍"
    TraceFeedback.THUMBS_DOWN -> "👎"
    TraceFeedback.NONE -> null
}

// ----- Prompt tab: system prompt + size breakdown -------------------------

/**
 * Shows the assembled system prompt with a per-section character count.
 * Useful for "why is my prompt so big?" investigations. We use char counts
 * as a proxy for tokens — close enough for budget estimation without
 * pulling in a tokenizer dependency.
 */
@Composable
private fun PromptTab(runtime: WeftRuntime) {
    val prompt = remember(runtime) { runtime.systemPrompt }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${prompt.length} chars · ~${prompt.length / CHARS_PER_TOKEN} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(prompt)) }) {
                Text("Copy")
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { /* read-only */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            readOnly = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

// Char-to-token proxy. Anthropic models average roughly 3.5–4 chars/token
// on English; using 4 as a round number keeps the estimate conservative
// (slightly under-reports tokens, which is the right direction for a
// budget guide).
private const val CHARS_PER_TOKEN = 4

// ----- Tools tab: registered tool catalog ---------------------------------

/**
 * Lists every registered tool with name + description. The Playground
 * sub-screen for ad-hoc invocation is wired in a future pass.
 */
@Composable
private fun ToolsTab(runtime: WeftRuntime) {
    val tools = remember(runtime) {
        runtime.tools.sortedBy { it.descriptor.name }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tools, key = { it.descriptor.name }) { tool ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tool.descriptor.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val tags = buildList {
                            if (tool.sideEffecting) add("side-effect")
                            if (tool.destructive) add("destructive")
                            if (tool.requiredPermissions.isNotEmpty()) {
                                add(tool.requiredPermissions.joinToString { it.name.lowercase() })
                            }
                        }
                        if (tags.isNotEmpty()) {
                            Text(
                                text = tags.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = tool.descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// ----- Cost tab: token + dollar totals ------------------------------------

@Composable
private fun CostTab(runtime: WeftRuntime) {
    val totals by runtime.usageStore.totals.collectAsState()
    val today = java.time.LocalDate.now().toString()
    val todayUsd = totals.byDay[today] ?: 0.0

    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CostRow("Today", "$%.4f".format(todayUsd))
        CostRow("Lifetime", "$%.4f".format(totals.lifetimeUsd))
        CostRow("Input tokens", totals.lifetimeInputTokens.toString())
        CostRow("Output tokens", totals.lifetimeOutputTokens.toString())
        totals.lastCallModelId?.let { CostRow("Last model", it) }
        HorizontalDivider()
        Text("By day", style = MaterialTheme.typography.labelMedium)
        totals.byDay.toSortedMap(compareByDescending { it }).forEach { (day, usd) ->
            CostRow(day, "$%.4f".format(usd))
        }
    }
}

@Composable
private fun CostRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// ----- Shared ---------------------------------------------------------------

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
