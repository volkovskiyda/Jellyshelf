# Composition tracing

A system trace normally shows Compose as a few opaque slices: `Recomposer:recompose`, `compose:lazy:prefetch`, `measure`. [Composition tracing](https://developer.android.com/develop/ui/compose/tooling/tracing) adds every composable function to the trace as a slice of its own, named with its file and line:

```
com.gmail.volkovskiyda.jellyshelf.ui.VideoRow (Common.kt:361)
```

So a long frame says *which* composables it spent its time in, and a scroll says how many rows it composed. It answers "what runs, and how often", not "how long does it take". While a trace records, every composable call writes a track event, which costs time. Read durations from these traces relatively, within one trace, and take absolute numbers from a benchmark run without tracing.

## What is set up

- **`:app`** depends on `androidx.compose.runtime:runtime-tracing` (BOM-managed) in the **`debug`** and **`benchmarkRelease`** build types only.
  - It is kept off `release` because the artifact brings `androidx.tracing:tracing-perfetto`. Its startup initializer asks `PackageManager` on every cold start whether a cold-start trace was requested: a binder call on the main thread at each launch. It also adds a DUMP-protected exported receiver. `app/build.gradle.kts` has the details.
  - It is kept off `nonMinifiedRelease` too, so the baseline profile stays a profile of the app that ships.
  - The composable names themselves are in every APK regardless: the Compose compiler writes them.
- **`:baselineprofile`** needs nothing more. The guide says to add `tracing-perfetto` and `tracing-perfetto-binary` to the benchmark module, but `benchmark-macro` already depends on both at runtime scope.
- **No APK carries the native half** (`tracing-perfetto-binary`, a 3.6 MB `.so` per ABI). Whatever records the trace sideloads it into the app through that receiver: Android Studio, Macrobenchmark or the script below. The receiver verifies a checksum before loading anything.

The app's own `Jellyshelf.*` sections (`util/Metrics.kt`) appear in the same trace, so a composable can be read against the span it ran under.

## Three ways to record

### Android Studio

1. Pick the variant in **Build Variants**:
   - **`debug`** shows what composes and recomposes, with your own signed-in data. Its timings are useless, since the build is debuggable.
   - **`benchmarkRelease`** is minified and profileable, so its timings are close to release. It installs as `….benchmark`, a separate, signed-out app; see [BASELINE-PROFILE.md](BASELINE-PROFILE.md).
2. Open the **Profiler** and record a **System Trace** while you use the app.
3. Composables appear under the main thread's frames, and in the flame chart. Double-clicking one opens its source.

Android Studio does the sideload itself. API 30 or newer.

### Macrobenchmark

Add one instrumentation argument to any `JourneyBenchmark` run:

```sh
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.gmail.volkovskiyda.jellyshelf.baselineprofile.JourneyBenchmark#libraryScroll \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.fullTracing.enable=true
```

- **Where the traces land:** each iteration's trace goes under `baselineprofile/build/outputs/connected_android_test_additional_output/`, with composable names in it.
- **Checking the handshake:** if a trace has none, look in logcat for the handshake. `"exitCode":1` means it worked. A `requiredVersion` that differs from the binary's means a Compose BOM bump moved the app's `tracing-perfetto` away from the version `benchmark-macro` ships.
- **Don't mix runs:** never compare a run with this flag against one without it. The overhead lands in exactly the metrics the benchmark reports.
- **`playback` is the exception.** It records with its own Perfetto config (`playbackTraceConfig` in `JourneyBenchmark.kt`), which has no `track_event` data source. Its traces stay free of composable names until one is added there.

### Terminal

`scripts/composition-trace.sh` does what Android Studio does, for a scripted run or a device the IDE is not attached to.

```sh
scripts/composition-trace.sh                     # the running debug app, 10 s; drive it meanwhile
scripts/composition-trace.sh --cold -t 8         # force-stop it and trace the next cold launch
scripts/composition-trace.sh -p com.gmail.volkovskiyda.jellyshelf.benchmark --cold
```

- **What it does:** it asks the app which `tracing-perfetto-binary` version it needs, then takes that version from Gradle's cache or Google Maven. It pushes the library for the device's ABI and completes the handshake. Then it records `track_event` alongside scheduling, frame timeline and the app's atrace sections.
- **Output:** the trace goes to `build/traces/`, unless `-o` names a file. `ANDROID_SERIAL` picks the device.
- **`--cold` mode:** this is the only way to see the first frame's composition. A running-app trace starts after it. Under the hood it uses the library's cold-start request, which is stored on the device and used once.
- **Release builds:** a `release` APK has no receiver, so the script refuses it and says so.

## Reading a trace

Open it in [ui.perfetto.dev](https://ui.perfetto.dev).

- **Where composables appear:** they nest under the main thread's `Choreographer#doFrame` → `Recomposer:recompose` and the lazy-list prefetch slices.
- **What a duration includes:** a composable's duration includes everything it composed, so a parent always reads at least as long as its children.

To rank composables by time and count, query the trace (the **Query (SQL)** page, or `trace_processor_shell`):

```sql
select name, count(*) as calls, round(sum(dur) / 1e6, 2) as total_ms
from slice
where name like '%.kt:%)'
group by name
order by total_ms desc
limit 20;
```

A filter on the app's process name has to use the last 15 characters of the package (`shelf.benchmark`, `ellyshelf.debug`), because Linux truncates process names.

## Not set up yet: recomposition tracing

Compose Runtime Tracing 1.13 adds the state reads and writes that *caused* each recomposition, with a flow arrow from a write to the recomposition it triggered. It needs `runtime-tracing` 1.13.0 or newer, which arrives with Compose BOM 2026.11.00. As of this writing the line is still in beta, and the project's BOM is 2026.09.00.

After that BOM bump the dependency above already qualifies, and nothing else in the build has to change. Recording uses Google's `record_recomposition_trace.py` from [android/performance-samples](https://github.com/android/performance-samples/tree/main/RecompositionTracing).
