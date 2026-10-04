/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.filters.RequiresDevice;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@LargeTest
@RequiresDevice
@RunWith(AndroidJUnit4.class)
public class NativeTTSDeviceTest {

	private static final String TAG = "NativeTTSDeviceTest";
	private static final String PREF_TTS_LOOKAHEAD = "pref_tts_lookahead";
	private static final String LOCAL_TTS = "com.localtts";
	private static final String GOOGLE_TTS = "com.google.android.tts";
	private static final long INIT_TIMEOUT_SECONDS = 20;
	private static final long PLAYBACK_TIMEOUT_SECONDS = 90;

	@Test
	public void localTtsWithoutLookahead() throws InterruptedException {
		runConfiguredScenario(LOCAL_TTS, false);
	}

	@Test
	public void localTtsWithLookahead() throws InterruptedException {
		runConfiguredScenario(LOCAL_TTS, true);
	}

	@Test
	public void googleTtsWithoutLookahead() throws InterruptedException {
		runConfiguredScenario(GOOGLE_TTS, false);
	}

	@Test
	public void googleTtsWithLookahead() throws InterruptedException {
		runConfiguredScenario(GOOGLE_TTS, true);
	}

	private static void runConfiguredScenario(final String engine, final boolean lookahead)
			throws InterruptedException {

		final Context context = ApplicationProvider.getApplicationContext();
		final String defaultEngineBefore = findDefaultEngine(context);
		final SharedPreferences preferences =
				PreferenceManager.getDefaultSharedPreferences(context);
		final boolean hadLookaheadPreference = preferences.contains(PREF_TTS_LOOKAHEAD);
		final boolean originalLookahead = preferences.getBoolean(PREF_TTS_LOOKAHEAD, true);
		try {
			Assert.assertTrue("Unable to set lookahead=" + lookahead,
					preferences.edit().putBoolean(PREF_TTS_LOOKAHEAD, lookahead).commit());
			runScenario(context, engine, lookahead);
		} finally {
			final SharedPreferences.Editor editor = preferences.edit();
			if (hadLookaheadPreference) {
				editor.putBoolean(PREF_TTS_LOOKAHEAD, originalLookahead);
			} else {
				editor.remove(PREF_TTS_LOOKAHEAD);
			}
			Assert.assertTrue("Unable to restore lookahead preference", editor.commit());
			Assert.assertEquals("Explicit engine test changed the device default TTS",
					defaultEngineBefore, findDefaultEngine(context));
		}
		Log.i(TAG, "DEFAULT_ENGINE_PRESERVED engine=" + defaultEngineBefore);
		Log.w(TAG, "Callbacks verify sequencing, but emitted sound and separator identity "
				+ "still require a person to listen to this synthetic, non-private text");
	}

	private static String findDefaultEngine(final Context context) throws InterruptedException {
		final CountDownLatch initialized = new CountDownLatch(1);
		final int[] status = {TextToSpeech.ERROR};
		final TextToSpeech[] engine = new TextToSpeech[1];
		try {
			engine[0] = new TextToSpeech(context, result -> {
				status[0] = result;
				initialized.countDown();
			});
			Assert.assertTrue("Default TTS did not initialize",
					initialized.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
			Assert.assertEquals("Default TTS initialization failed",
					TextToSpeech.SUCCESS, status[0]);
			return engine[0].getDefaultEngine();
		} finally {
			if (engine[0] != null) {
				engine[0].shutdown();
			}
		}
	}

	private static void runScenario(
			final Context context,
			final String engine,
			final boolean lookahead) throws InterruptedException {

		final Diagnostics diagnostics = new Diagnostics(engine, lookahead);
		final RecordingListener listener = new RecordingListener();
		final NativeTTSManager manager =
				NativeTTSManager.createForTesting(context, engine, diagnostics);
		try {
			manager.setListener(listener);
			manager.setSpeed(1.0f);
			manager.readAloud(Collections.singletonList(new NativeTTSManager.TTSItem(
					"Old synthetic comment for the stop and replacement check. "
							+ "This contains no private information and should be interrupted.",
					900,
					0)));
			Assert.assertTrue(label(engine, lookahead, "old comment did not start"),
					listener.awaitHighlightCount(1));
			final Voice voiceBefore = manager.getVoiceForTesting();
			Assert.assertNotNull(label(engine, lookahead, "engine returned no selected voice"),
					voiceBefore);
			manager.stop();
			final int replacementEvent = diagnostics.markReplacement();
			listener.expectCompletion();
			manager.readAloud(syntheticComments());
			Assert.assertTrue(label(engine, lookahead, "replacement did not complete"),
					listener.awaitCompletion());
			Assert.assertEquals(label(engine, lookahead, "highlight order"),
					Arrays.asList(900, 101, 102, 103), listener.getHighlights());
			Assert.assertEquals(label(engine, lookahead, "TTS callback errors"),
					0, listener.getErrors());
			Assert.assertFalse(label(engine, lookahead, "manager remained active"),
					manager.isSpeaking());
			final Voice voiceAfter = manager.getVoiceForTesting();
			Assert.assertNotNull(label(engine, lookahead, "selected voice disappeared"),
					voiceAfter);
			Assert.assertEquals(label(engine, lookahead, "selected voice changed"),
					voiceBefore.getName(), voiceAfter.getName());
			Assert.assertEquals(label(engine, lookahead, "selected voice locale changed"),
					voiceBefore.getLocale(), voiceAfter.getLocale());
			Log.i(TAG, "VOICE_PRESERVED engine=" + engine + " lookahead=" + lookahead
					+ " name=" + voiceAfter.getName() + " locale=" + voiceAfter.getLocale());
			diagnostics.assertReplacementSynthesisOrder(replacementEvent, lookahead);
		} finally {
			manager.clearListener(listener);
			manager.shutdownForTesting();
		}
	}

	private static List<NativeTTSManager.TTSItem> syntheticComments() {
		return Arrays.asList(
				new NativeTTSManager.TTSItem(
						"First synthetic top level comment for physical device verification.",
						101,
						0),
				new NativeTTSManager.TTSItem(
						"Second synthetic reply, nested one level below the first comment.",
						102,
						1),
				new NativeTTSManager.TTSItem(
						"Third synthetic comment, returning to the top level.",
						103,
						0));
	}

	private static String label(
			final String engine, final boolean lookahead, final String message) {
		return engine + " lookahead=" + lookahead + ": " + message;
	}

	private static final class RecordingListener implements NativeTTSManager.Listener {
		private final List<Integer> mHighlights = new ArrayList<>();
		private final AtomicBoolean mSpeakingSeen = new AtomicBoolean();
		private CountDownLatch mCompletion = new CountDownLatch(1);
		private int mErrors;

		@Override
		public synchronized void onTTSStateChanged(final boolean isSpeaking) {
			if (isSpeaking) {
				mSpeakingSeen.set(true);
			} else if (mSpeakingSeen.get()) {
				mCompletion.countDown();
			}
		}

		@Override
		public synchronized void onUtteranceStarted(final int position) {
			mHighlights.add(position);
			notifyAll();
		}

		@Override
		public synchronized void onTTSError() {
			mErrors++;
		}

		synchronized boolean awaitHighlightCount(final int count) throws InterruptedException {
			final long deadline = System.currentTimeMillis()
					+ TimeUnit.SECONDS.toMillis(PLAYBACK_TIMEOUT_SECONDS);
			while (mHighlights.size() < count) {
				final long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) {
					return false;
				}
				wait(remaining);
			}
			return true;
		}

		synchronized void expectCompletion() {
			mSpeakingSeen.set(false);
			mCompletion = new CountDownLatch(1);
		}

		boolean awaitCompletion() throws InterruptedException {
			return mCompletion.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		synchronized List<Integer> getHighlights() {
			return new ArrayList<>(mHighlights);
		}

		synchronized int getErrors() {
			return mErrors;
		}
	}

	private static final class Diagnostics implements NativeTTSManager.DiagnosticsListener {
		private final List<Event> mEvents = new ArrayList<>();
		private final String mEngine;
		private final boolean mLookahead;

		private Diagnostics(final String engine, final boolean lookahead) {
			mEngine = engine;
			mLookahead = lookahead;
		}

		@Override
		public synchronized void onTTSEvent(
				final String id,
				final String event,
				final long timeMs,
				final int depth,
				final int code) {
			final Event record = new Event(id, event, timeMs, mEvents.size(), depth);
			mEvents.add(record);
			Log.i(TAG, "engine=" + mEngine + " lookahead=" + mLookahead + " id=" + id
					+ " event=" + event + " time=" + timeMs + " depth=" + depth
					+ " code=" + code);
		}

		synchronized int markReplacement() {
			return mEvents.size();
		}

		synchronized void assertReplacementSynthesisOrder(
				final int firstEvent, final boolean lookahead) {
			final List<Event> submissions = new ArrayList<>();
			int maxDepth = 0;
			final int depthLimit = lookahead ? 2 : 1;
			for (int i = firstEvent; i < mEvents.size(); i++) {
				final Event event = mEvents.get(i);
				Assert.assertTrue(label(mEngine, lookahead,
						"negative diagnostics queue depth " + event.mDepth), event.mDepth >= 0);
				Assert.assertTrue(label(mEngine, lookahead,
						"diagnostics queue depth exceeded " + depthLimit),
						event.mDepth <= depthLimit);
				maxDepth = Math.max(maxDepth, event.mDepth);
				if ("submitted".equals(event.mName) && event.mId.contains("-speech-")) {
					submissions.add(event);
				}
			}
			Log.i(TAG, "MAX_QUEUE_DEPTH engine=" + mEngine + " lookahead=" + lookahead
					+ " observed=" + maxDepth + " limit=" + depthLimit);
			Assert.assertEquals(label(mEngine, lookahead, "submitted speech count"),
					3, submissions.size());
			int previousStartOrder = -1;
			int previousDoneOrder = -1;
			for (final Event submission : submissions) {
				final Event start = find(firstEvent, submission.mId, "start");
				final Event done = find(firstEvent, submission.mId, "done");
				Assert.assertTrue(label(mEngine, lookahead, "speech start order"),
						start.mOrder > previousStartOrder);
				Assert.assertTrue(label(mEngine, lookahead, "speech completion order"),
						done.mOrder > previousDoneOrder);
				previousStartOrder = start.mOrder;
				previousDoneOrder = done.mOrder;
			}
			for (int i = 0; i < submissions.size() - 1; i++) {
				final Event currentDone = find(firstEvent, submissions.get(i).mId, "done");
				final Event upcomingSubmission = submissions.get(i + 1);
				if (lookahead) {
					Assert.assertTrue(label(mEngine, true,
							"upcoming speech was not submitted before current completion"),
							upcomingSubmission.mOrder < currentDone.mOrder);
				} else {
					Assert.assertTrue(label(mEngine, false,
							"upcoming speech was submitted before sequential completion"),
							upcomingSubmission.mOrder > currentDone.mOrder);
				}
				final Event upcomingBegin = findOrNull(
						firstEvent, upcomingSubmission.mId, "begin_synthesis");
				if (upcomingBegin == null) {
					Log.i(TAG, "OVERLAP_CAPABILITY engine=" + mEngine
							+ " lookahead=" + lookahead + " supported=unknown"
							+ " reason=begin_synthesis_callback_missing");
				} else {
					final boolean overlapped = upcomingBegin.mOrder < currentDone.mOrder;
					Log.i(TAG, "OVERLAP_CAPABILITY engine=" + mEngine
							+ " lookahead=" + lookahead + " supported=" + overlapped
							+ " current=" + currentDone.mId
							+ " doneMs=" + currentDone.mTimeMs
							+ " upcoming=" + upcomingBegin.mId
							+ " beginSynthesisMs=" + upcomingBegin.mTimeMs);
				}
			}
		}

		private Event find(final int firstEvent, final String id, final String name) {
			for (int i = firstEvent; i < mEvents.size(); i++) {
				final Event event = mEvents.get(i);
				if (id.equals(event.mId) && name.equals(event.mName)) {
					return event;
				}
			}
			Assert.fail(label(mEngine, mLookahead, "missing " + name + " for " + id));
			throw new AssertionError();
		}

		private Event findOrNull(final int firstEvent, final String id, final String name) {
			for (int i = firstEvent; i < mEvents.size(); i++) {
				final Event event = mEvents.get(i);
				if (id.equals(event.mId) && name.equals(event.mName)) {
					return event;
				}
			}
			return null;
		}
	}

	private static final class Event {
		private final String mId;
		private final String mName;
		private final long mTimeMs;
		private final int mOrder;
		private final int mDepth;

		private Event(
				final String id,
				final String name,
				final long timeMs,
				final int order,
				final int depth) {
			mId = id;
			mName = name;
			mTimeMs = timeMs;
			mOrder = order;
			mDepth = depth;
		}
	}
}
