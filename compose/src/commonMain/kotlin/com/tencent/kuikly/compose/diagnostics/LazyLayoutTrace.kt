package com.tencent.kuikly.compose.diagnostics

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Generic, switchable trace for lazy-layout measure and placement.
 *
 * Deliberately free of any product vocabulary: this library records what a
 * measure pass did, never what the content means. Correlation values arriving
 * from a host application are opaque — compared for equality so records can be
 * joined, never parsed, persisted, or interpreted.
 *
 * Why it exists: a consumer reading `LazyListState.layoutInfo` only sees a
 * snapshot after the fact, so it cannot tell which measure or placement first
 * diverged from what the user was shown. That question can only be answered
 * from inside the measure pass.
 *
 * The switch is a compile-time constant so a disabled build can be shown to do
 * no work at all, rather than merely to stay quiet. Nothing here allocates,
 * formats, or traverses when it is false.
 */
public object LazyLayoutTraceConfig {
    /**
     * Single build-time source of truth. A host that enables tracing must flip
     * this and its own constant together; a build where the two disagree is
     * rejected by [requireConsistentWith] rather than producing records that
     * cover only one side of the boundary.
     */
    public const val ENABLED: Boolean = false

    /**
     * Fails fast when the host's switch and this library's switch disagree.
     * Such a build yields a chain that is missing one layer while appearing
     * healthy, which is worse than no tracing at all.
     */
    public fun requireConsistentWith(hostEnabled: Boolean) {
        if (hostEnabled != ENABLED) {
            error(
                "lazy layout trace switch mismatch: host=$hostEnabled library=$ENABLED — " +
                    "both sides must be built from the same source of truth"
            )
        }
    }
}

/**
 * Opaque correlation values supplied by the host.
 *
 * [targetToken] identifies the item the host cares about without disclosing
 * what it is; this library only ever compares it.
 */
public data class LazyTraceContext(
    public val traceSession: String,
    public val cycle: Long,
    public val layoutGeneration: Long,
    public val targetToken: String?
)

/** Frame identity, required by every record that claims a moment. */
public data class LazyTraceFrame(
    public val frameSequence: Long,
    public val frameTimeNanos: Long
)

/** Where inside the library a record was produced. */
public enum class LazyTraceStage {
    MeasureStart,
    MeasureResult,
    Placement,
    NativeCommit
}

/**
 * One measure or placement observation. Fields are the geometry a caller needs
 * to tell "laid out" from "visible" — a distinction that index ranges alone
 * cannot express.
 */
public data class LazyTraceMeasureRecord(
    public val stage: LazyTraceStage,
    public val function: String,
    public val frame: LazyTraceFrame,
    public val constraintsMaxMainAxis: Int,
    public val viewportStartPx: Int,
    public val viewportEndPx: Int,
    public val scrollToBeConsumed: Float,
    public val firstVisibleIndex: Int,
    public val firstVisibleScrollOffset: Int,
    public val visibleItemCount: Int,
    public val totalItemCount: Int,
    public val targetIndex: Int,
    public val targetOffsetPx: Int,
    public val targetSizePx: Int,
    public val coveredPx: Int,
    public val gapPx: Int
)

/** Emission seam owned by the host; released with the composition that made it. */
public fun interface LazyTraceSink {
    public fun onMeasureRecord(context: LazyTraceContext, record: LazyTraceMeasureRecord)
}

/**
 * Host-injected trace handle. Held for the lifetime of one lazy layout and
 * dropped with it — this library never keeps a process-wide sink, so it cannot
 * become a second owner of anything.
 */
public class LazyLayoutTrace(
    private val context: LazyTraceContext,
    private val sink: LazyTraceSink
) {
    /**
     * Records a measure observation. The record is built inside the lambda so
     * that with tracing disabled the geometry is never gathered — the check
     * happens before any field is computed, which matters because this sits in
     * the measure hot path.
     */
    public inline fun measure(build: () -> LazyTraceMeasureRecord) {
        if (!LazyLayoutTraceConfig.ENABLED) return
        emit(build())
    }

    public fun emit(record: LazyTraceMeasureRecord) {
        if (!LazyLayoutTraceConfig.ENABLED) return
        sink.onMeasureRecord(context, record)
    }

    /**
     * The host's opaque item token, for equality against a layout item's key.
     * Exposed rather than the whole context so a caller cannot start reading
     * correlation values as if they carried meaning.
     */
    public fun targetTokenOrNull(): String? = context.targetToken
}

/**
 * Covered/uncovered split of a viewport, unioning clipped item spans.
 *
 * Summing spans instead would double-count sticky or overlapping items and
 * report a viewport as fuller than it is — hiding the empty band this is meant
 * to expose.
 */
public fun lazyTraceViewportCoverage(
    viewportStartPx: Int,
    viewportEndPx: Int,
    itemSpans: List<Pair<Int, Int>>
): Pair<Int, Int> {
    if (viewportEndPx <= viewportStartPx) return 0 to 0
    val clipped = ArrayList<Pair<Int, Int>>(itemSpans.size)
    for ((start, end) in itemSpans) {
        val top = if (start > viewportStartPx) start else viewportStartPx
        val bottom = if (end < viewportEndPx) end else viewportEndPx
        if (bottom > top) clipped.add(top to bottom)
    }
    clipped.sortBy { it.first }
    var covered = 0
    var runStart = 0
    var runEnd = 0
    var open = false
    for ((top, bottom) in clipped) {
        if (!open) {
            runStart = top
            runEnd = bottom
            open = true
        } else if (top <= runEnd) {
            if (bottom > runEnd) runEnd = bottom
        } else {
            covered += runEnd - runStart
            runStart = top
            runEnd = bottom
        }
    }
    if (open) covered += runEnd - runStart
    val extent = viewportEndPx - viewportStartPx
    return covered to (if (extent > covered) extent - covered else 0)
}

/**
 * How a host hands a trace to the lazy layouts inside its own composition.
 *
 * Static because it is read in the measure path and must not cause
 * recomposition, and null by default so a host that provides nothing pays
 * nothing. Scoped to the provider's composition, so the handle dies with the
 * screen that created it rather than outliving it as global state.
 */
public val LocalLazyLayoutTrace: androidx.compose.runtime.ProvidableCompositionLocal<LazyLayoutTrace?> =
    staticCompositionLocalOf { null }
