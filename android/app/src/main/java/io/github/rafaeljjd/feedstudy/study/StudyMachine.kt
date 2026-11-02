package io.github.rafaeljjd.feedstudy.study

enum class Phase {
    ENROLL,
    CONSENT,
    USAGE_ACCESS,
    EXPORT_GUIDE_1,
    EXPORT_IMPORT_1,
    RATING_1,
    VALUATION_1,
    ASSIGNING,
    TASK,
    TRACKING,
    EXPORT_GUIDE_2,
    EXPORT_IMPORT_2,
    RATING_2,
    VALUATION_2,
    FINISHING,
    DONE,
    INELIGIBLE,
}

enum class StudyEvent {
    ENROLLED,
    CONSENTED,
    USAGE_GRANTED,
    EXPORT_REQUESTED,
    EXPORT_IMPORTED,
    RATING_DONE,
    VALUATION_DONE,
    ASSIGNED_WITH_TASK,
    ASSIGNED_NO_TASK,
    FOUND_INELIGIBLE,
    TASK_DONE,
    TRACKING_ELAPSED,
    COMPLETED,
}

/**
 * The participant's path through the study. WTAs come before randomization, so baseline
 * valuations are measured before treatment.
 */
object StudyMachine {
    private val TRANSITIONS: Map<Pair<Phase, StudyEvent>, Phase> = mapOf(
        (Phase.ENROLL to StudyEvent.ENROLLED) to Phase.CONSENT,
        (Phase.CONSENT to StudyEvent.CONSENTED) to Phase.USAGE_ACCESS,
        (Phase.USAGE_ACCESS to StudyEvent.USAGE_GRANTED) to Phase.EXPORT_GUIDE_1,
        (Phase.EXPORT_GUIDE_1 to StudyEvent.EXPORT_REQUESTED) to Phase.EXPORT_IMPORT_1,
        (Phase.EXPORT_IMPORT_1 to StudyEvent.EXPORT_IMPORTED) to Phase.RATING_1,
        (Phase.RATING_1 to StudyEvent.RATING_DONE) to Phase.VALUATION_1,
        (Phase.VALUATION_1 to StudyEvent.VALUATION_DONE) to Phase.ASSIGNING,
        (Phase.ASSIGNING to StudyEvent.ASSIGNED_WITH_TASK) to Phase.TASK,
        (Phase.ASSIGNING to StudyEvent.ASSIGNED_NO_TASK) to Phase.TRACKING,
        (Phase.ASSIGNING to StudyEvent.FOUND_INELIGIBLE) to Phase.INELIGIBLE,
        (Phase.TASK to StudyEvent.TASK_DONE) to Phase.TRACKING,
        (Phase.TRACKING to StudyEvent.TRACKING_ELAPSED) to Phase.EXPORT_GUIDE_2,
        (Phase.EXPORT_GUIDE_2 to StudyEvent.EXPORT_REQUESTED) to Phase.EXPORT_IMPORT_2,
        (Phase.EXPORT_IMPORT_2 to StudyEvent.EXPORT_IMPORTED) to Phase.RATING_2,
        (Phase.RATING_2 to StudyEvent.RATING_DONE) to Phase.VALUATION_2,
        (Phase.VALUATION_2 to StudyEvent.VALUATION_DONE) to Phase.FINISHING,
        (Phase.FINISHING to StudyEvent.COMPLETED) to Phase.DONE,
    )

    /** Returns the next phase, or null when the event does not apply in this phase. */
    fun next(phase: Phase, event: StudyEvent): Phase? = TRANSITIONS[phase to event]
}

object StudyClock {
    fun trackingEndMillis(treatedAtMillis: Long, trackingDays: Int, daySeconds: Long): Long =
        treatedAtMillis + trackingDays * daySeconds * 1000

    fun isTrackingElapsed(
        nowMillis: Long,
        treatedAtMillis: Long,
        trackingDays: Int,
        daySeconds: Long,
    ): Boolean = nowMillis >= trackingEndMillis(treatedAtMillis, trackingDays, daySeconds)

    /** Whole study days since treatment, starting at 0. */
    fun dayNumber(nowMillis: Long, treatedAtMillis: Long, daySeconds: Long): Long =
        ((nowMillis - treatedAtMillis).coerceAtLeast(0)) / (daySeconds * 1000)
}
