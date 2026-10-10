# Native TTS lookahead verification

Settings → Accessibility → Read aloud lookahead applies to the next reading
session. Enabled: at most two speech segments are outstanding (current plus one
upcoming). Disabled: at most one. A long comment can occupy both slots; lookahead
is bounded by speech segments, not whole comments. Separators are additional
ordered audio events, not extra speech slots.

## Physical device procedure

1. Install `build/outputs/apk/debug/RedReader-master-debug.apk` on a physical device.
   Record device/Android version, Local TTS/MarkMad Android-TTS version, Cedar
   version, Pocket TTS model and chosen voice. Do not record server credentials.
   Configure Local TTS to use Cedar Pocket in its own settings. Set Android TTS
   and RedReader playback to 1×. Record the voice selection before and after.
2. Use a thread containing three medium-length comments, nested replies, sibling
   replies, a return to the parent level, a top-level comment, an empty comment,
   and a comment longer than Android's input limit containing emoji near a split.
   Run the same thread with lookahead off and on. Repeat at least three times.
   Use fresh text or explicitly account for engine/server caching when measuring
   Cedar synthesis latency; a cached response is not a server synthesis benchmark.
   Include a URL-only Markdown GIF link and a link with a readable label. The
   URL-only link should be silent; the readable label should be narrated without
   its destination URL or Markdown brackets/parentheses.
3. Capture only app diagnostics (PowerShell):
   `adb -s DEVICE logcat -v monotonic NativeTTSManager:D '*:S' > tts-events.log`.
   Avoid collecting engine logs that might include comment text or URLs. For
   server timing, use sanitized request timestamps and durations only.
4. Match speech IDs and sessions. In lookahead mode, the next speech submission
   must precede the current speech's playback completion, with outstanding speech
   depth never above two. In sequential mode the next submission follows completion.
   Submission proves Android accepted a request, not that Cedar received it.
5. For actual overlap, establish that the next speech's synthesis-begin or first
   audio event occurs before current playback completion. Correlate sanitized
   Cedar request-arrival timestamps to prove the next server request arrives
   before current playback ends. Align clocks or use a common recording with a
   known clock offset. Callback timestamps alone cannot prove HTTP arrival.
   Measure audible inter-comment gaps separately, including separator duration.
6. Listen: one separator immediately before each new comment, no separator
   between split chunks, and none over the preceding comment. Top-level comments,
   deeper replies, siblings and shallower replies retain their distinct sounds.
   Highlighting/scrolling must begin with the spoken comment, never with its
   lookahead submission, synthesis, or separator. Check short comments too.
7. Press Stop during synthesis, speech and a separator. Navigate away, jump to
   another comment, and replace the session while a lookahead request is pending.
   Old speech/sounds must cease promptly; delayed callbacks must not move focus,
   restart reading, or show an error for deliberate cancellation.
      Lock the device during playback and verify the lock-screen controls remain
      available: pause/resume, previous/next comment, and stop. Resume restarts the
      current comment from its beginning. Navigating away removes the notification;
      denying notification permission leaves speech working but hides lock-screen
      controls on Android 13 and later.
8. Temporarily make Cedar unavailable using its own controls. A rejected request
   or synthesis failure must stop once and show the existing read-aloud error;
   later comments must not be rapidly skipped. Restore the server and retry.
9. Repeat steps 2–7 in both modes with another Android engine (e.g. Google TTS).
   Restart RedReader after switching the Android default engine, since its TTS
   client retains the engine selected at initialization. Preserve each engine's
   selected voice; restore the original default engine
   after manual testing. Record gaps and any missing diagnostics separately.

## Limits and interpretation

Android's [TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
accepts speech and earcons asynchronously. QUEUE_ADD preserves requested order;
it does not promise synthesis/playback concurrency. Packaged WAV earcons use
`addEarcon`/`playEarcon` with explicit read grants to the selected TTS engine.
Android 12+ registers content URIs; earlier versions retain resource registration
after the grant makes RedReader visible to the engine. Separator playback shares the ordered Android queue
without waiting in RedReader before submitting speech.

[UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener)
exposes synthesis begin/audio callbacks on API 24+, playback start/completion,
errors, and stop callbacks on API 23+. Audio availability is generated data, not
proof of audible playback. Callback delivery can lag, engines may buffer audio,
and device output latency remains. Missing engine callbacks or serial synthesis
must be reported as limitations, not counted as successful overlap.
On API 24+, an engine that never supplies first-audio callbacks can still speak,
but cannot trigger the gated comment focus. API 21–23 have no first-audio callback
and retain playback-start focus. Sequential fallback changes queue depth, not
these Android callback limitations.

At the supplied Cedar synthesis ratio of approximately 0.82 seconds per second of
audio at 1×, overlap could hide much of synthesis latency when the current segment
is long enough. Unequal segment lengths, networking, engine serialization and
initial startup can still leave gaps. No direct Cedar connection, pronunciation
change, engine selection override or voice selection override is needed.

## Results

Automated and physical-device results are recorded here after validation. Audible
separator placement, UI focus and the Cedar backend/voice configuration require
human confirmation; instrumentation alone does not establish those facts.

Device available on 4 October 2026: Motorola edge 50 neo, Android 16 (API 36).
Default engine: `com.localtts`, version 0.1.0; user confirms Cedar Pocket is
configured. Second engine: `com.google.android.tts`, version
`googletts.google-speech-apk_20260817.01_p0.966249458`.

Physical instrumentation: four independent Local TTS/Google TTS × lookahead
on/off scenarios passed at 1×, including listener order, Stop/replacement,
completion and unchanged default engine. User confirmed audible transition
sounds and prompt interruption. The first run took 68.18 seconds. Examples from
monotonic callbacks: Local TTS lookahead started upcoming synthesis 723 ms before
the preceding completion; Google TTS lookahead started it 825 and 846 ms before
preceding completion. Sequential runs started synthesis after completion. These
are Android callback measurements, not Cedar HTTP request-arrival measurements
or measured audible gap reductions.

Local TTS supplied no separator start/completion callbacks in this run, despite
the audible separators confirmed by the user. Local TTS also reported one speech
start about nine seconds before its first audio callback. RedReader uses Android
playback-start plus first-audio callbacks for comment focus on API 24+, so this
early start cannot move focus while audio is still being generated. Neither
synthesis nor first-audio events alone move focus. API 21–23 only expose the
playback-start callback and cannot use this extra gate. Device UI confirmation
is needed, and no exact speaker-output timing is claimed.

The installed RedReader uses a different certificate from the debug build.
Physical instrumentation used an isolated debug application ID
`org.quantumbadger.redreader.fork.ttsverification`, built using a temporary Gradle
init script under ignored `build/`. The checked-in fork application ID remains
unchanged, and the installed application/data were preserved.

To reproduce the isolated build without changing repository configuration, create
an init script outside tracked sources containing:

```groovy
gradle.beforeProject { project ->
	project.pluginManager.withPlugin('com.android.application') {
		project.android.buildTypes.debug.applicationIdSuffix = '.ttsverification'
	}
}
```

Run `./gradlew.bat -I PATH_TO_SCRIPT assembleDebug assembleDebugAndroidTest`, install
both APKs, then run:

```powershell
adb -s DEVICE shell am instrument -w -r -e class org.quantumbadger.redreader.audio.NativeTTSDeviceTest org.quantumbadger.redreader.fork.ttsverification.test/androidx.test.runner.AndroidJUnitRunner
```

These tests require both engine packages and a real device. Each scenario restores
the app's original lookahead setting, keeps the device default engine unchanged,
and shuts down its isolated TTS client. Use continuous filtered logcat capture
during the test to avoid losing early events from Android's ring buffer:

```powershell
adb -s DEVICE logcat -v monotonic NativeTTSDeviceTest:I NativeTTSManager:D '*:S' > tts-device-events.log
```

Rebuild normally afterward to restore the standard debug APK application ID.

Final local validation: `./gradlew.bat test lint pmd checkstyle assembleDebug
assembleRelease` passed, with 126 JVM tests across 19 suites and no failures/errors,
including 30 NativeTTSManager regressions. Android lint reported no errors or
warnings. The debug and signed release APKs are respectively:

- `build/outputs/apk/debug/RedReader-master-debug.apk`
- `build/outputs/apk/release/RedReader-master-release.apk`

A repository-signed release update installed successfully over the existing
RedReader for manual UI verification. A later final instrumentation run was
deliberately interrupted because manual thread playback was using the same
engine; that overlapping run is excluded from the validation results.

The completed final device run passed all four tests in 52.479 seconds after
manual playback stopped. It included the first-audio focus gate, selected-voice
name/locale preservation, unchanged default engine and strict diagnostic depth
assertions. Observed results:

| Engine | Lookahead | Maximum speech depth | Next synthesis versus current completion |
| --- | --- | --- | --- |
| Local TTS / Cedar Pocket Alba | On | 2 | 717 ms and 808 ms before |
| Local TTS / Cedar Pocket Alba | Off | 1 | 19 ms and 22 ms after |
| Google TTS / English US | On | 2 | 822 ms and 854 ms before |
| Google TTS / English US | Off | 1 | 13 ms and 10 ms after |

The tests assert the next Android submission precedes current completion only
with lookahead enabled, and validate ordered playback/highlight callbacks for
each comment. Text-free diagnostics and runner results are in ignored
`build/tts-device-complete-events.log` and `build/tts-device-complete-results.txt`.
Temporary verification apps were removed after testing.

Manual audible separator placement and interruption were confirmed by the user.
The final manual RecyclerView focus/scroll and navigation verdict for both
engines/modes is still pending; callback tests do not substitute for that UI check.

Markdown URL narration follow-up: URL-only inline links and image alt text are
omitted; readable link labels are retained. Bare HTTP/HTTPS URLs are stripped
case-insensitively, including trailing slashes, and Markdown autolinks leave no
angle-bracket wrappers. Seven URL-cleanup tests and all 30 native TTS regressions
passed, along with lint, PMD, Checkstyle and the signed release build.

Version 1.26.6 (124) validation: all 133 JVM tests across 20 suites passed,
with no failures or errors. Android lint, PMD, Checkstyle and `assembleRelease`
passed. The signed APK is `build/outputs/apk/release/RedReader-master-release.apk`.

## Missing earcons investigation, 5 October 2026

During user-triggered Cedar streaming playback on the Motorola device, speech
continued but each separator failed in Local TTS's Android MediaPlayer queue.
Filtered diagnostics showed `FileNotFoundException: No package found for authority`
for RedReader's `android.resource://` earcon URI. This is a resource access failure;
the reproduction does not establish that streaming itself causes the failure.

Earcons now use cached copies of the packaged WAV files exposed through a dedicated,
non-exported FileProvider limited to `cache/tts-earcons/`. Registration grants the
selected TTS engine read access to each content URI. Android 12+ registers these
URIs directly; older versions retain resource URIs after the grant establishes
package visibility. Speech and separator ordering,
lookahead depth and selected voice behavior are unchanged. Physical-device audible
confirmation for this change is recorded below.

Local validation passed: `test lint pmd checkstyle assembleRelease`, with all 138
JVM tests passing, including 35 native TTS regressions. Windows Robolectric uses
a test-only URI mapping because AndroidX FileProvider assumes Android path
separators. Tests check the cached WAV bytes, URI read grants and narrow provider
XML; they do not establish actual cross-process provider playback. The rebuilt
signed release APK is `build/outputs/apk/release/RedReader-master-release.apk`.

The rebuilt signed release update installed successfully on the Motorola device
on 5 October 2026 using `adb install -r`, preserving application data. Installed
version: 1.26.6 (124). The user confirmed that earcons are audible again with Cedar
streaming after installing this update.
