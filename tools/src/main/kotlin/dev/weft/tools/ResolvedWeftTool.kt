package dev.weft.tools

import dev.weft.contracts.ResolvedTool

/**
 * Concrete substrate implementation of [ResolvedTool]. Wraps a fully
 * constructed [WeftTool] so `:contracts` doesn't need a Koog dep.
 *
 * `:harness:agents` consumes this to extract the tool's
 * `ToolDescriptor` (LLM advertising) + register it into the
 * `ToolRegistry` (execution dispatch).
 */
class ResolvedWeftTool(val tool: WeftTool<*, *>) : ResolvedTool {
    override val name: String = tool.descriptor.name
}
