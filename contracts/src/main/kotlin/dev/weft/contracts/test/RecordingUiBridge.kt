package dev.weft.contracts.test

import dev.weft.contracts.AskKind
import dev.weft.contracts.UIUpdate
import dev.weft.contracts.UiBridge
import dev.weft.contracts.UserAnswer

/**
 * UiBridge stub that records calls and returns programmable answers.
 * Useful for tests; the production app injects an Android-specific impl.
 *
 * Lives in main sources so other modules' tests can consume it (same pattern
 * as MockLLMClient / MockScript / MockMiddleware).
 */
class RecordingUiBridge(
    private val answers: ArrayDeque<UserAnswer> = ArrayDeque(),
    private val destructiveAnswers: ArrayDeque<Boolean> = ArrayDeque(),
) : UiBridge {

    val askCalls: MutableList<Triple<String, AskKind, List<String>>> = mutableListOf()
    val destructiveCalls: MutableList<Pair<String, String?>> = mutableListOf()
    val emittedUpdates: MutableList<UIUpdate> = mutableListOf()

    fun queueAnswer(answer: UserAnswer) { answers.addLast(answer) }

    fun queueConfirmation(confirm: Boolean) { destructiveAnswers.addLast(confirm) }

    override suspend fun askUser(question: String, kind: AskKind, options: List<String>): UserAnswer {
        askCalls += Triple(question, kind, options)
        return answers.removeFirstOrNull() ?: UserAnswer.Cancelled
    }

    override suspend fun confirmDestructive(action: String, body: String?): Boolean {
        destructiveCalls += action to body
        return destructiveAnswers.removeFirstOrNull() ?: false
    }

    override suspend fun showInfo(title: String, body: String?) {
        infoCalls += title to body
    }

    val infoCalls: MutableList<Pair<String, String?>> = mutableListOf()

    override suspend fun emit(update: UIUpdate) {
        emittedUpdates += update
    }
}
