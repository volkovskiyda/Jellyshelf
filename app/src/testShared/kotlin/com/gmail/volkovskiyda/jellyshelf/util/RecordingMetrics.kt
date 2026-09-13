package com.gmail.volkovskiyda.jellyshelf.util

/**
 * A [Metrics] whose three sinks are lists, for tests that want to assert on what was measured —
 * and for the many that simply need *a* [Metrics] to construct something with.
 *
 * Stands in for both seams at once: it is the [MetricsSink] Kotzilla would be and the [CloudTraces]
 * Firebase would be. The system-trace side is not faked, because `androidx.tracing` is a no-op
 * whenever nothing is recording, which in a test is always.
 *
 * The clock is [elapsed], a plain `var` the test moves by hand — durations are then exact rather
 * than "about zero", and nothing here touches `SystemClock`, which a JVM unit test cannot call.
 */
class RecordingMetrics : MetricsSink, CloudTraces {

    /** The monotonic clock [metrics] reads. Advance it to give a span a duration. */
    var elapsed: Long = 0L

    /** Every Firebase trace opened, in order, started or not. */
    val traces = mutableListOf<RecordedTrace>()

    /** Kotzilla marks, as `track/label` so a test can assert on both halves. */
    val marks = mutableListOf<String>()

    /** Kotzilla log lines. Where a measured duration ends up. */
    val logs = mutableListOf<String>()

    /** Ids passed to [suspendTrace], in order. */
    val suspendTraces = mutableListOf<String>()

    /** The facade under test, wired to this recorder on both sides. */
    val metrics: Metrics = Metrics(this, this, elapsedRealtime = { elapsed })

    override fun start(id: String): CloudTrace = RecordedTrace(id).also { traces += it }

    override suspend fun <T> suspendTrace(id: String, block: suspend () -> T): T {
        suspendTraces += id
        return block()
    }

    override fun mark(label: String, track: String) {
        marks += "$track/$label"
    }

    override fun log(message: String) {
        logs += message
    }

    /** The one trace opened under [id]. Fails the test if it was opened twice, or never. */
    fun trace(id: String): RecordedTrace = traces.single { it.id == id }

    /** The ids of every trace that was actually reported — an abandoned span leaves none. */
    fun reported(): List<String> = traces.filter { it.stopped }.map { it.id }
}

/** One Firebase trace, remembered rather than sent. */
class RecordedTrace(val id: String) : CloudTrace {

    val attributes = mutableMapOf<String, String>()
    val metrics = mutableMapOf<String, Long>()
    var stopped = false
        private set

    override fun attribute(name: String, value: String) {
        attributes[name] = value
    }

    override fun metric(name: String, value: Long) {
        metrics[name] = value
    }

    override fun stop() {
        stopped = true
    }
}

/** A [Metrics] for the tests that need one but assert nothing about it. */
fun fakeMetrics(): Metrics = RecordingMetrics().metrics
