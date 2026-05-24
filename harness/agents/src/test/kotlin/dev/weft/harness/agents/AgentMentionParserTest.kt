package dev.weft.harness.agents

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AgentMentionParserTest : StringSpec({

    "explicit @mention extracts name and body" {
        val m = AgentMentionParser.parse("@writer Draft an intro paragraph.")
        m.agentName shouldBe "writer"
        m.body shouldBe "Draft an intro paragraph."
    }

    "@mention with no body returns empty body" {
        val m = AgentMentionParser.parse("@researcher")
        m.agentName shouldBe "researcher"
        m.body shouldBe ""
    }

    "leading and trailing whitespace are stripped" {
        val m = AgentMentionParser.parse("   @writer   draft this   ")
        m.agentName shouldBe "writer"
        m.body shouldBe "draft this"
    }

    "no mention returns null name and trimmed body" {
        val m = AgentMentionParser.parse("just a regular message")
        m.agentName shouldBe null
        m.body shouldBe "just a regular message"
    }

    "double-at is treated as literal text, not a mention" {
        val m = AgentMentionParser.parse("@@writer email syntax")
        m.agentName shouldBe null
        m.body shouldBe "@@writer email syntax"
    }

    "hyphen and underscore are allowed in agent names" {
        val m = AgentMentionParser.parse("@my-writer_v2 keep going")
        m.agentName shouldBe "my-writer_v2"
        m.body shouldBe "keep going"
    }

    "uppercase letters in name fail the regex and fall through" {
        val m = AgentMentionParser.parse("@Writer something")
        m.agentName shouldBe null
        m.body shouldBe "@Writer something"
    }

    "inner @ aborts the match" {
        val m = AgentMentionParser.parse("@name@something rest")
        m.agentName shouldBe null
        m.body shouldBe "@name@something rest"
    }

    "multi-line body is preserved verbatim after the mention" {
        val m = AgentMentionParser.parse("@writer first line\nsecond line")
        m.agentName shouldBe "writer"
        m.body shouldBe "first line\nsecond line"
    }

    "empty input returns null and empty body" {
        val m = AgentMentionParser.parse("")
        m.agentName shouldBe null
        m.body shouldBe ""
    }

    "bare @ with no name falls through" {
        val m = AgentMentionParser.parse("@ rest")
        m.agentName shouldBe null
        m.body shouldBe "@ rest"
    }
})
