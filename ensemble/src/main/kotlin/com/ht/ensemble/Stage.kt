package com.ht.ensemble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { PLANNING, RUNNING, REINFORCING, INTEGRATING, REVIEWING, KICKOFF, RESUMING, SPRINT, STANDUP, INSPECTING, QA, REPORTING, DELIVERING, PAUSED, DONE, STOPPED, FAILED }

enum class CardStatus { WAITING, RUNNING, CHECKING, SWAPPED, DONE, FAILED }

/** One unit of work on the stage: a task (conductor) or a ticket (company). */
data class Card(
    val id: String,
    val title: String,
    val who: String,
    val vendor: String,
    val status: CardStatus = CardStatus.WAITING,
    val doing: String = "",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val after: List<String> = emptyList(),
    val tool: Boolean = false,
    val peer: String = "",
    val round: Int = 0,
)

/** One file in a company project: path, size, latest review score (0 when not yet reviewed). */
data class FileState(val path: String, val chars: Int, val score: Double)

/**
 * Everything a UI needs to draw a live job: who leads, the phase, every card, the board, and —
 * for company projects — sprint, deadline, quality and files. Immutable; replaced on every change.
 */
data class Stage(
    val jobId: String,
    val goal: String,
    val center: String,
    val centerVendor: String,
    val phase: Phase,
    val cards: List<Card> = emptyList(),
    val progress: Double = 0.0,
    val note: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val board: List<String> = emptyList(),
    val sprint: Int = 0,
    val deadline: Long = 0,
    val quality: Double = 0.0,
    val files: List<FileState> = emptyList(),
    val budgetMinutes: Int = 0,
)

/** Live stages of every job a team runs. Collect [stages] from a UI, or read [get] once. */
class StageHub {
    private val flow = MutableStateFlow<Map<String, Stage>>(emptyMap())
    val stages: StateFlow<Map<String, Stage>> get() = flow

    operator fun get(jobId: String): Stage? = flow.value[jobId]
    fun put(s: Stage) = flow.update { it + (s.jobId to s) }
    fun edit(jobId: String, f: (Stage) -> Stage) = flow.update { m -> m[jobId]?.let { m + (jobId to f(it)) } ?: m }
    fun card(jobId: String, id: String, f: (Card) -> Card) = edit(jobId) { st -> st.copy(cards = st.cards.map { if (it.id == id) f(it) else it }) }
    fun addCards(jobId: String, cards: List<Card>) = edit(jobId) { it.copy(cards = it.cards + cards) }
    fun drop(jobId: String) = flow.update { it - jobId }
}
