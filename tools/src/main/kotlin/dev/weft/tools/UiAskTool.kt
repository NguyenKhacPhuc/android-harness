package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.AskKind
import dev.weft.contracts.UserAnswer
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable

class UiAskTool(ctx: WeftContext) : WeftTool<UiAskTool.Args, UiAskTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "ui_ask",
        description = "Ask the user a question and wait for the answer. " +
            "Use to fill in missing info or to let the user pick from options. " +
            "kind='yes_no' (default), 'choice' (requires options), or 'free_text'.",
        requiredParameters = listOf(
            ToolParameterDescriptor("question", "The question to show the user.", ToolParameterType.String),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor(
                "kind",
                "Answer shape: 'yes_no', 'choice', or 'free_text'. Defaults to 'yes_no'.",
                ToolParameterType.String,
            ),
            ToolParameterDescriptor(
                "options",
                "List of options for kind='choice'. Required for choice; ignored otherwise.",
                ToolParameterType.List(ToolParameterType.String),
            ),
        ),
    ),
) {

    @Serializable
    enum class Kind { yes_no, choice, free_text }

    @Serializable
    data class Args(
        val question: String,
        val kind: Kind = Kind.yes_no,
        val options: List<String> = emptyList(),
    )

    @Serializable
    data class Result(
        val answer: String? = null,
        val cancelled: Boolean = false,
    )

    override suspend fun executeWeft(args: Args): Result {
        if (args.kind == Kind.choice && args.options.isEmpty()) {
            return Result(answer = null, cancelled = true)
        }
        val answer = ui.askUser(
            question = args.question,
            kind = when (args.kind) {
                Kind.yes_no -> AskKind.YES_NO
                Kind.choice -> AskKind.CHOICE
                Kind.free_text -> AskKind.FREE_TEXT
            },
            options = args.options,
        )
        return when (answer) {
            is UserAnswer.YesNo -> Result(answer = answer.value.toString())
            is UserAnswer.Choice -> Result(answer = answer.value)
            is UserAnswer.Text -> Result(answer = answer.value)
            UserAnswer.Cancelled -> Result(cancelled = true)
        }
    }
}
