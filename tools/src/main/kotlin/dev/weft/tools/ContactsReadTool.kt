package dev.weft.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.weft.contracts.ContactFilter
import dev.weft.contracts.Permission
import dev.weft.tools.WeftContext
import dev.weft.tools.WeftTool
import kotlinx.serialization.Serializable

/**
 * Read user contacts with simple filters. Requires CONTACTS_READ runtime
 * permission; the substrate's permission gate will refuse and hint the LLM
 * to call ui_request_permission first.
 */
public class ContactsReadTool(ctx: WeftContext) : WeftTool<ContactsReadTool.Args, ContactsReadTool.Result>(
    ctx = ctx,
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    descriptor = ToolDescriptor(
        name = "contacts_read",
        description = "Read the user's contacts. Apply filters to narrow the list. " +
            "Returns display name and emails / phone numbers. " +
            "Weft doesn't expose contact-write in v1.",
        // Required placeholder String — see SystemInfoTools.kt (Anthropic
        // crashes on tools with zero required params).
        requiredParameters = listOf(
            ToolParameterDescriptor(
                "context",
                "Why you're reading contacts, e.g. 'user asked'. Any short string; ignored.",
                ToolParameterType.String,
            ),
        ),
        optionalParameters = listOf(
            ToolParameterDescriptor("nameContains", "Substring match on display name (case-insensitive).", ToolParameterType.String),
            ToolParameterDescriptor("hasEmail", "If true, only contacts with at least one email.", ToolParameterType.Boolean),
            ToolParameterDescriptor("hasPhone", "If true, only contacts with at least one phone number.", ToolParameterType.Boolean),
            ToolParameterDescriptor("limit", "Maximum number of contacts to return (1–200). Defaults to 50.", ToolParameterType.Integer),
        ),
    ),
    requiredPermissions = setOf(Permission.CONTACTS_READ),
) {

    @Serializable
    public data class Args(
        val context: String = "",
        val nameContains: String? = null,
        val hasEmail: Boolean? = null,
        val hasPhone: Boolean? = null,
        val limit: Int = 50,
    )

    @Serializable
    public data class Contact(
        val id: String,
        val displayName: String,
        val emails: List<String> = emptyList(),
        val phones: List<String> = emptyList(),
    )

    @Serializable
    public data class Result(val items: List<Contact>)

    override suspend fun executeWeft(args: Args): Result {
        val results = os.contacts.read(
            ContactFilter(
                nameContains = args.nameContains,
                hasEmail = args.hasEmail,
                hasPhone = args.hasPhone,
                limit = args.limit.coerceIn(1, 200),
            ),
        )
        return Result(items = results.map { Contact(it.id, it.displayName, it.emails, it.phones) })
    }
}
