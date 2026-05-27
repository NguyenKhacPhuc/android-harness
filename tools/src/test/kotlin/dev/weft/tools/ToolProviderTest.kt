package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.ResolvedTool
import dev.weft.contracts.ToolActivationSink
import dev.weft.contracts.ToolMetadata
import dev.weft.contracts.ToolProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Stage 2 of [docs/architecture/tool-provider.md] —
 * [ToolProvider]/[ToolMetadata]/[EagerToolProvider]/[FindToolTool]
 * + the [ToolActivationSink] side channel.
 *
 * Excluded from this file because they live in `:harness:agents`:
 * the strategy graph node that actually applies activations
 * (covered by integration tests once Stage 2 dogfoods through
 * Undercurrent).
 */
class ToolProviderTest : StringSpec({

    val ctx = stubContext()

    "EagerToolProvider exposes every wrapped tool" {
        val alpha = stubTool(ctx, "alpha_tool", "Alpha.")
        val beta = stubTool(ctx, "beta_tool", "Beta.")
        val provider = EagerToolProvider(listOf(alpha, beta))

        provider.available.map { it.name } shouldContainExactly listOf("alpha_tool", "beta_tool")
        provider.available.all { it.alwaysOn } shouldBe true
    }

    "EagerToolProvider rejects duplicate tool names" {
        val a1 = stubTool(ctx, "dupe", "First.")
        val a2 = stubTool(ctx, "dupe", "Second.")
        shouldThrow<IllegalArgumentException> {
            EagerToolProvider(listOf(a1, a2))
        }
    }

    "EagerToolProvider honors per-tool overrides" {
        val t = stubTool(ctx, "foo", "Foo.")
        val provider = EagerToolProvider(
            tools = listOf(t),
            overrides = mapOf("foo" to ToolMetadataOverride(category = "memory", alwaysOn = false)),
        )
        val meta = provider.available.single()
        meta.category shouldBe "memory"
        meta.alwaysOn shouldBe false
    }

    "EagerToolProvider.resolve returns ResolvedWeftTool for known names" {
        runTest {
            val t = stubTool(ctx, "foo", "Foo.")
            val provider = EagerToolProvider(listOf(t))
            val resolved = provider.resolve("foo")
            resolved.shouldNotBeNull()
            (resolved as ResolvedWeftTool).tool.descriptor.name shouldBe "foo"
        }
    }

    "EagerToolProvider.resolve returns null for unknown names" {
        runTest {
            val provider = EagerToolProvider(listOf(stubTool(ctx, "foo", "Foo.")))
            provider.resolve("nope").shouldBeNull()
        }
    }

    "compositeToolProvider concatenates available + delegates resolve" {
        runTest {
            val p1 = EagerToolProvider(listOf(stubTool(ctx, "a", "A.")))
            val p2 = EagerToolProvider(listOf(stubTool(ctx, "b", "B.")))
            val composite = compositeToolProvider(p1, p2)
            composite.available.map { it.name } shouldContainExactly listOf("a", "b")
            composite.resolve("a").shouldNotBeNull()
            composite.resolve("b").shouldNotBeNull()
            composite.resolve("c").shouldBeNull()
        }
    }

    "compositeToolProvider fails fast on name collision" {
        val p1 = EagerToolProvider(listOf(stubTool(ctx, "shared", "From p1.")))
        val p2 = EagerToolProvider(listOf(stubTool(ctx, "shared", "From p2.")))
        shouldThrow<IllegalArgumentException> {
            compositeToolProvider(p1, p2)
        }
    }

    "FindToolTool ranks by query relevance" {
        runTest {
            val provider = FakeProvider(
                listOf(
                    ToolMetadata("camera_capture", "Open the system camera and take a photo.", category = "media"),
                    ToolMetadata("send_email", "Send an email via the user's default mail app.", category = "mail"),
                    ToolMetadata("notify_show", "Show a notification.", category = "system", alwaysOn = true),
                    ToolMetadata("scan_barcode", "Decode QR / barcodes in an image.", category = "media"),
                ),
            )
            val tool = FindToolTool(ctx, provider)
            val result = tool.executeWeft(FindToolTool.Args(query = "scan", limit = 5))
            // 'scan' is in scan_barcode (name match) and in "Decode QR…"
            // is not — only one match expected.
            result.tools.first().name shouldBe "scan_barcode"
        }
    }

    "FindToolTool excludes alwaysOn tools from results" {
        runTest {
            val provider = FakeProvider(
                listOf(
                    ToolMetadata("notify_show", "Show a notification.", alwaysOn = true),
                    ToolMetadata("notify_schedule", "Schedule a notification."),
                ),
            )
            val tool = FindToolTool(ctx, provider)
            val result = tool.executeWeft(FindToolTool.Args(query = "notify"))
            result.tools.map { it.name } shouldContain "notify_schedule"
            result.tools.none { it.name == "notify_show" } shouldBe true
        }
    }

    "FindToolTool honors category filter" {
        runTest {
            val provider = FakeProvider(
                listOf(
                    ToolMetadata("camera_capture", "Take a photo.", category = "media"),
                    ToolMetadata("send_email", "Send mail.", category = "mail"),
                ),
            )
            val tool = FindToolTool(ctx, provider)
            val result = tool.executeWeft(
                FindToolTool.Args(query = "send", category = "media"),
            )
            // 'send' matches send_email by token, but category=media filters it out
            // before ranking.
            result.tools.none { it.name == "send_email" } shouldBe true
        }
    }

    "FindToolTool writes activation names into the ToolActivationSink" {
        runTest {
            val provider = FakeProvider(
                listOf(
                    ToolMetadata("camera_capture", "Take a photo.", category = "media"),
                    ToolMetadata("scan_barcode", "Decode QR.", category = "media"),
                ),
            )
            val tool = FindToolTool(ctx, provider)
            val sink = ToolActivationSink()
            // Inject sink into the coroutine context — same as WeftAgent.send does.
            val result = withContext(sink) {
                tool.executeWeft(FindToolTool.Args(query = "scan"))
            }
            result.activated shouldBe result.tools.size
            sink.drain() shouldContainExactly result.tools.map { it.name }
        }
    }

    "FindToolTool returns activated=0 when no sink is present" {
        runTest {
            val provider = FakeProvider(
                listOf(ToolMetadata("camera_capture", "Take a photo.")),
            )
            val tool = FindToolTool(ctx, provider)
            // No withContext(sink) — simulates direct test invocation.
            val result = tool.executeWeft(FindToolTool.Args(query = "camera"))
            result.tools shouldHaveSize 1
            result.activated shouldBe 0
        }
    }

    "ToolActivationSink dedupes when find_tool is called twice with overlap" {
        runTest {
            val sink = ToolActivationSink()
            sink.record(listOf("a", "b", "c"))
            sink.record(listOf("b", "c", "d")) // overlap with prior
            sink.drain() shouldContainExactly listOf("a", "b", "c", "d")
        }
    }
})

/** Minimum [ToolProvider] for FindToolTool tests — exposes a fixed catalog, never resolves. */
private class FakeProvider(override val available: List<ToolMetadata>) : ToolProvider {
    override suspend fun resolve(name: String): ResolvedTool? = null
}

/** Minimum [WeftTool] subclass — just enough descriptor for [ToolProvider] tests. */
private fun stubTool(ctx: dev.weft.tools.WeftContext, name: String, description: String): WeftTool<*, *> =
    object : WeftTool<StubArgs, String>(
        ctx = ctx,
        argsType = typeToken<StubArgs>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(
            name = name,
            description = description,
            requiredParameters = listOf(
                ToolParameterDescriptor("context", "ignored", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    ) {
        override suspend fun executeWeft(args: StubArgs): String =
            error("stub tool — not expected to execute in provider tests")
    }

@Serializable
private data class StubArgs(val context: String = "")
