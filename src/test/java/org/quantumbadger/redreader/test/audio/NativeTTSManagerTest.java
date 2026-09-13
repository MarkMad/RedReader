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

package org.quantumbadger.redreader.test.audio;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.quantumbadger.redreader.audio.NativeTTSManager;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = NativeTTSManagerTest.TestTTS.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeTTSManagerTest {

	private NativeTTSManager manager;
	private RecordingListener listener;

	@Before
	public void setUp() throws Exception {
		TestTTS.reset();
		final Field singleton = NativeTTSManager.class.getDeclaredField("sInstance");
		singleton.setAccessible(true);
		singleton.set(null, null);
		manager = NativeTTSManager.getInstance(RuntimeEnvironment.getApplication());
		listener = new RecordingListener();
		manager.setListener(listener);
	}

	private void read(final String... texts) {
		final List<NativeTTSManager.TTSItem> items = new ArrayList<>();
		for (int i = 0; i < texts.length; i++) {
			items.add(new NativeTTSManager.TTSItem(texts[i], i));
		}
		manager.readAloud(items);
	}

	private static void idle() {
		Shadows.shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void splitsAtLimitAndPreservesSurrogatePairs() {
		final String text = "a".repeat(TextToSpeech.getMaxSpeechInputLength() - 1)
				+ "\uD83D\uDE00" + "ending";
		read(text);
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(1, TestTTS.spoken.size());
		TestTTS.progress.onDone(TestTTS.ids.get(0));
		idle();
		Assert.assertEquals(2, TestTTS.spoken.size());
		Assert.assertEquals(text, String.join("", TestTTS.spoken));
		for (final String chunk : TestTTS.spoken) {
			Assert.assertTrue(chunk.length() <= TextToSpeech.getMaxSpeechInputLength());
			Assert.assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
		}
		TestTTS.progress.onDone(TestTTS.ids.get(1));
		idle();
		Assert.assertFalse(manager.isSpeaking());
	}

	@Test
	public void rejectedSpeakAdvancesAndClearsPlaying() {
		TestTTS.speakResult = TextToSpeech.ERROR;
		read("one", "two");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(Arrays.asList("one", "two"), TestTTS.spoken);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertEquals(2, listener.errors);
	}

	@Test
	public void staleCallbackCannotAdvanceRestartedQueue() throws Exception {
		read("old");
		TestTTS.init(TextToSpeech.SUCCESS);
		final String old = TestTTS.ids.get(0);
		manager.stop();
		read("new first", "new second");
		final Thread callbackThread = new Thread(() -> {
			TestTTS.progress.onStart(old);
			TestTTS.progress.onDone(old);
			TestTTS.progress.onError(old);
		});
		callbackThread.start();
		callbackThread.join();
		idle();
		Assert.assertEquals(Arrays.asList("old", "new first"), TestTTS.spoken);
		Assert.assertTrue(manager.isSpeaking());
		Assert.assertEquals(0, listener.errors);
		Assert.assertEquals(0, listener.starts);
		TestTTS.progress.onDone(TestTTS.ids.get(1));
		idle();
		Assert.assertEquals("new second", TestTTS.spoken.get(2));
	}

	@Test
	public void clearListenerStopsPendingInitialization() {
		read("hello");
		Assert.assertTrue(manager.isSpeaking());
		manager.clearListener(listener);
		final int notifications = listener.states;
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertTrue(TestTTS.spoken.isEmpty());
		Assert.assertEquals(notifications, listener.states);
	}

	@Test
	public void stopWhileInitializingDoesNotSpeakLater() {
		read("cancel");
		manager.stop();
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertTrue(TestTTS.spoken.isEmpty());
		read("next");
		Assert.assertEquals(Arrays.asList("next"), TestTTS.spoken);
	}

	@Test
	public void initializationFailureCanBeRetried() {
		read("first");
		TestTTS.init(TextToSpeech.ERROR);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertEquals(1, listener.errors);
		Assert.assertEquals(1, TestTTS.shutdowns);
		read("retry");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(Arrays.asList("retry"), TestTTS.spoken);
	}

	@Test
	public void missingLanguageCanBeRetried() {
		TestTTS.language = TextToSpeech.LANG_MISSING_DATA;
		read("first");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertEquals(1, listener.errors);
		TestTTS.language = TextToSpeech.LANG_AVAILABLE;
		read("retry");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(Arrays.asList("retry"), TestTTS.spoken);
	}

	@Test
	public void oldOwnerCannotStopNewOwner() {
		final RecordingListener replacement = new RecordingListener();
		manager.setListener(replacement);
		read("current");
		TestTTS.init(TextToSpeech.SUCCESS);
		manager.clearListener(listener);
		Assert.assertTrue(manager.isSpeaking());
		TestTTS.progress.onStart(TestTTS.ids.get(0));
		idle();
		Assert.assertEquals(1, replacement.starts);
		Assert.assertEquals(0, listener.starts);
	}

	@Test
	public void clearedListenerReceivesNoQueuedCallbacks() {
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		TestTTS.progress.onStart(TestTTS.ids.get(0));
		TestTTS.progress.onDone(TestTTS.ids.get(0));
		manager.clearListener(listener);
		final int notifications = listener.states;
		idle();
		Assert.assertEquals(0, listener.starts);
		Assert.assertEquals(notifications, listener.states);
		Assert.assertFalse(manager.isSpeaking());
	}

	private static class RecordingListener implements NativeTTSManager.Listener {
		int starts;
		int errors;
		int states;

		@Override
		public void onTTSStateChanged(final boolean speaking) {
			states++;
		}

		@Override
		public void onUtteranceStarted(final int position) {
			starts++;
		}

		@Override
		public void onTTSError() {
			errors++;
		}
	}

	@Implements(value = TextToSpeech.class, callThroughByDefault = false)
	public static class TestTTS {
		static TextToSpeech.OnInitListener initListener;
		static int language;
		static int speakResult;
		static int shutdowns;
		static UtteranceProgressListener progress;
		static final List<String> spoken = new ArrayList<>();
		static final List<String> ids = new ArrayList<>();

		@Implementation
		protected void __constructor__(final Context context,
				final TextToSpeech.OnInitListener callback) {
			initListener = callback;
		}

		static void reset() {
			initListener = null;
			progress = null;
			spoken.clear();
			ids.clear();
			speakResult = TextToSpeech.SUCCESS;
			language = TextToSpeech.LANG_AVAILABLE;
			shutdowns = 0;
		}

		static void init(final int status) {
			initListener.onInit(status);
			idle();
		}

		@Implementation
		protected static int getMaxSpeechInputLength() {
			return 4000;
		}

		@Implementation
		protected int setLanguage(final Locale locale) {
			return language;
		}

		@Implementation
		protected int speak(final CharSequence text, final int queueMode,
				final Bundle params, final String id) {
			spoken.add(text.toString());
			ids.add(id);
			return speakResult;
		}

		@Implementation
		protected int stop() {
			return TextToSpeech.SUCCESS;
		}

		@Implementation
		protected void shutdown() {
			shutdowns++;
		}

		@Implementation
		protected int setOnUtteranceProgressListener(final UtteranceProgressListener callback) {
			progress = callback;
			return TextToSpeech.SUCCESS;
		}
	}
}
