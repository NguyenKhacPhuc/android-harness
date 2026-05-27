package dev.weft.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class MathEvalToolTest : StringSpec({
    val tool = MathEvalTool(stubContext())

    "evaluates simple arithmetic with precedence" {
        runTest {
            val r = tool.executeWeft(MathEvalTool.Args("2 + 3 * 4"))
            r.ok shouldBe true
            r.value shouldBe 14.0
        }
    }

    "handles parentheses" {
        runTest {
            val r = tool.executeWeft(MathEvalTool.Args("(2 + 3) * 4"))
            r.value shouldBe 20.0
        }
    }

    "right-associative power" {
        runTest {
            // 2^3^2 = 2^(3^2) = 2^9 = 512
            val r = tool.executeWeft(MathEvalTool.Args("2 ^ 3 ^ 2"))
            r.value shouldBe 512.0
        }
    }

    "supports functions and constants" {
        runTest {
            val r = tool.executeWeft(MathEvalTool.Args("sqrt(2) * pi"))
            r.ok shouldBe true
            r.value!! shouldBe (Math.sqrt(2.0) * Math.PI plusOrMinus 1e-9)
        }
    }

    "percentage math without LLM digit-flip" {
        runTest {
            // Classic tax/tip case the model gets wrong
            val r = tool.executeWeft(MathEvalTool.Args("450 * 0.18 + 12.5"))
            r.value!! shouldBe (93.5 plusOrMinus 1e-9)
        }
    }

    "rejects unknown identifier" {
        runTest {
            val r = tool.executeWeft(MathEvalTool.Args("nope"))
            r.ok shouldBe false
            r.value.shouldBeNull()
            r.error.shouldNotBeNull()
        }
    }

    "rejects trailing junk" {
        runTest {
            val r = tool.executeWeft(MathEvalTool.Args("1+2 xyz"))
            r.ok shouldBe false
        }
    }
})
