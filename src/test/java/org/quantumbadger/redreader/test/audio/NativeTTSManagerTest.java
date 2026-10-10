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
import android.content.Intent;
import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.XmlResourceParser;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.core.content.FileProvider;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.audio.NativeTTSManager;
import org.quantumbadger.redreader.audio.NativeTTSPlaybackService;
import org.quantumbadger.redreader.audio.TTSEarconPlayer;
import org.quantumbadger.redreader.common.General;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {NativeTTSManagerTest.TestTTS.class,
		NativeTTSManagerTest.TestSoundPool.class,
		NativeTTSManagerTest.TestFileProvider.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeTTSManagerTest {

	private NativeTTSManager manager;
	private RecordingListener listener;

	@Before
	public void setUp() throws Exception {
		TestTTS.reset();
		TestSoundPool.reset();
		final PackageInfo engine = new PackageInfo();
		engine.packageName = "com.localtts";
		engine.applicationInfo = new ApplicationInfo();
		engine.applicationInfo.packageName = engine.packageName;
		Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
				.installPackage(engine);
		final Field singleton = NativeTTSManager.class.getDeclaredField("sInstance");
		singleton.setAccessible(true);
		singleton.set(null, null);
		manager = NativeTTSManager.getInstance(RuntimeEnvironment.getApplication());
		setLookahead(true);
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
		Assert.assertEquals(2, TestTTS.spoken.size());
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
	public void rejectedSpeakStopsPlaybackWithOneError() {
		TestTTS.speakResult = TextToSpeech.ERROR;
		read("one", "two");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(Arrays.asList("one"), TestTTS.spoken);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertEquals(1, listener.errors);
	}

	@Test
	public void manyRejectedSpeaksStopAfterFirstSubmission() {
		TestTTS.speakResult = TextToSpeech.ERROR;
		final String[] texts = new String[10000];
		Arrays.fill(texts, "rejected");
		read(texts);
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertEquals(1, TestTTS.spoken.size());
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
		Assert.assertEquals(Arrays.asList("old", "new first", "new second"), TestTTS.spoken);
		Assert.assertTrue(manager.isSpeaking());
		Assert.assertEquals(0, listener.errors);
		Assert.assertEquals(0, listener.starts);
		TestTTS.progress.onDone(TestTTS.ids.get(1));
		idle();
		Assert.assertEquals(3, TestTTS.spoken.size());
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
	public void pauseAndResumeRestartsCurrentComment() {
		read("first", "second");
		TestTTS.init(TextToSpeech.SUCCESS);
		final String currentId = TestTTS.ids.get(0);
		TestTTS.progress.onStart(currentId);
		TestTTS.progress.onAudioAvailable(currentId, new byte[] {1});
		idle();

		manager.pause();
		Assert.assertTrue(manager.isPaused());
		Assert.assertFalse(manager.isSpeaking());
		manager.resume();

		Assert.assertFalse(manager.isPaused());
		Assert.assertTrue(manager.isSpeaking());
		Assert.assertEquals(Arrays.asList("first", "second", "first", "second"),
				TestTTS.spoken);
	}

	@Test
	public void commentNavigationRespectsQueueBoundaries() {
		read("first", "second", "third");
		TestTTS.init(TextToSpeech.SUCCESS);

		Assert.assertFalse(manager.skipToPreviousComment());
		Assert.assertTrue(manager.skipToNextComment());
		Assert.assertEquals(Arrays.asList("first", "second", "second", "third"),
				TestTTS.spoken);
		Assert.assertTrue(manager.skipToPreviousComment());
		Assert.assertEquals(Arrays.asList(
				"first", "second", "second", "third", "first", "second"),
				TestTTS.spoken);
		Assert.assertTrue(manager.skipToNextComment());
		Assert.assertTrue(manager.skipToNextComment());
		Assert.assertFalse(manager.skipToNextComment());
	}

	@Test
	public void mediaServicePublishesGenericNotificationAndRoutesActions() {
		read("first", "second", "third");
		TestTTS.init(TextToSpeech.SUCCESS);
		final Context context = RuntimeEnvironment.getApplication();
		final ServiceController<NativeTTSPlaybackService> controller =
				Robolectric.buildService(NativeTTSPlaybackService.class).create();
		controller.get().onStartCommand(new Intent(context, NativeTTSPlaybackService.class)
				.setAction("org.quantumbadger.redreader.tts.UPDATE"), 0, 1);

		final Notification notification = Shadows.shadowOf(controller.get())
				.getLastForegroundNotification();
		Assert.assertNotNull(notification);
		Assert.assertEquals("Read aloud",
				notification.extras.getString(Notification.EXTRA_TITLE));
		Assert.assertEquals(4, notification.actions.length);

		controller.get().onStartCommand(new Intent(context, NativeTTSPlaybackService.class)
				.setAction("org.quantumbadger.redreader.tts.PLAY_PAUSE"), 0, 2);
		Assert.assertTrue(manager.isPaused());
		controller.get().onStartCommand(new Intent(context, NativeTTSPlaybackService.class)
				.setAction("org.quantumbadger.redreader.tts.PLAY_PAUSE"), 0, 3);
		Assert.assertTrue(manager.isSpeaking());
		controller.get().onStartCommand(new Intent(context, NativeTTSPlaybackService.class)
				.setAction("org.quantumbadger.redreader.tts.NEXT"), 0, 4);
		Assert.assertEquals(Arrays.asList(
				"first", "second", "first", "second", "second", "third"), TestTTS.spoken);
		controller.get().onStartCommand(new Intent(context, NativeTTSPlaybackService.class)
				.setAction("org.quantumbadger.redreader.tts.STOP"), 0, 5);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertFalse(manager.isPaused());
		controller.destroy();
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
	public void registersReadableEarconsAndGrantsDefaultEngineAccess() throws Exception {
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		final Context context = RuntimeEnvironment.getApplication();
		Assert.assertEquals(TTSEarconPlayer.Earcon.values().length,
				TestTTS.registeredEarcons.size());
		final int engineUid = context.getPackageManager()
				.getApplicationInfo("com.localtts", 0).uid;
		final File earconDirectory = new File(context.getCacheDir(), "tts-earcons");
		for (int i = 0; i < TestTTS.registeredUris.size(); i++) {
			final Uri uri = TestTTS.registeredUris.get(i);
			Assert.assertEquals("content", uri.getScheme());
			Assert.assertEquals(context.getPackageName() + ".ttsearcons",
					uri.getAuthority());
			Assert.assertTrue(uri.getPath().startsWith("/tts_earcons/"));
			Assert.assertEquals(PackageManager.PERMISSION_GRANTED,
					context.checkUriPermission(uri, -1, engineUid,
							Intent.FLAG_GRANT_READ_URI_PERMISSION));
			final File earconFile = new File(earconDirectory, uri.getLastPathSegment());
			Assert.assertEquals(earconDirectory.getCanonicalPath(),
					earconFile.getCanonicalFile().getParent());
			try (FileInputStream input = new FileInputStream(earconFile)) {
				Assert.assertTrue(input.read() >= 0);
			}
		}
	}

	@Test
	public void earconProviderExposesOnlyDedicatedCacheDirectory() throws Exception {
		final Context context = RuntimeEnvironment.getApplication();
		int pathCount = 0;
		try (XmlResourceParser parser = context.getResources().getXml(R.xml.tts_earcon_paths)) {
			int event;
			while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
				if (event == XmlPullParser.START_TAG && "cache-path".equals(parser.getName())) {
					pathCount++;
					Assert.assertEquals("tts_earcons", parser.getAttributeValue(null, "name"));
					Assert.assertEquals("tts-earcons/", parser.getAttributeValue(null, "path"));
				}
			}
		}
		Assert.assertEquals(1, pathCount);
	}

	@Test
	@Config(sdk = 30)
	public void olderAndroidGrantsAccessBeforeLegacyRegistration() throws Exception {
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		final Context context = RuntimeEnvironment.getApplication();
		final int engineUid = context.getPackageManager()
				.getApplicationInfo("com.localtts", 0).uid;
		final File earconDirectory = new File(context.getCacheDir(), "tts-earcons");
		final File[] earconFiles = earconDirectory.listFiles();
		Assert.assertNotNull(earconFiles);
		Assert.assertEquals(TTSEarconPlayer.Earcon.values().length, earconFiles.length);
		for (final File earconFile : earconFiles) {
			final Uri uri = FileProvider.getUriForFile(context,
					context.getPackageName() + ".ttsearcons", earconFile);
			Assert.assertEquals(PackageManager.PERMISSION_GRANTED,
					context.checkUriPermission(uri, -1, engineUid,
							Intent.FLAG_GRANT_READ_URI_PERMISSION));
		}
		Assert.assertTrue(TestTTS.registeredUris.isEmpty());
		Assert.assertEquals(TTSEarconPlayer.Earcon.values().length,
				TestTTS.registeredPackages.size());
		for (final String packageName : TestTTS.registeredPackages) {
			Assert.assertEquals(context.getPackageName(), packageName);
		}
		for (final int resourceId : TestTTS.registeredResources) {
			Assert.assertTrue(resourceId != 0);
		}
	}

	@Test
	public void earconRegistrationFailureStopsInitializationOnce() {
		TestTTS.registrationFailureNumber = 3;
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertTrue(TestTTS.spoken.isEmpty());
		Assert.assertEquals(3, TestTTS.registrationCalls);
		Assert.assertEquals(1, TestTTS.shutdowns);
		Assert.assertEquals(1, listener.errors);
	}

	@Test
	public void missingDefaultEngineStopsInitializationOnce() {
		TestTTS.defaultEngine = null;
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertTrue(TestTTS.registeredUris.isEmpty());
		Assert.assertEquals(1, TestTTS.shutdowns);
		Assert.assertEquals(1, listener.errors);
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
		TestTTS.progress.onAudioAvailable(TestTTS.ids.get(0), new byte[] {1});
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

	@Test
	public void selectsEarconFromCommentDepthTransition() {
		Assert.assertEquals(
				TTSEarconPlayer.Earcon.SYNTH_TICK,
				NativeTTSManager.selectEarconForComment(-1, 0));
		Assert.assertEquals(
				TTSEarconPlayer.Earcon.MUTED_MARIMBA,
				NativeTTSManager.selectEarconForComment(-1, 2));
		Assert.assertEquals(
				TTSEarconPlayer.Earcon.MUTED_MARIMBA,
				NativeTTSManager.selectEarconForComment(1, 2));
		Assert.assertEquals(
				TTSEarconPlayer.Earcon.DIGITAL_POP,
				NativeTTSManager.selectEarconForComment(2, 2));
		Assert.assertEquals(
				TTSEarconPlayer.Earcon.DESCENDING_CHIME,
				NativeTTSManager.selectEarconForComment(3, 1));
		Assert.assertEquals(
				TTSEarconPlayer.Earcon.SYNTH_TICK,
				NativeTTSManager.selectEarconForComment(3, 0));
	}

	@Test
	public void stoppingDuringEarconCancelsSpeech() {
		readComment("cancel");
		Assert.assertEquals(1, TestTTS.earcons.size());
		Assert.assertEquals(Arrays.asList("cancel"), TestTTS.spoken);
		manager.stop();
		Assert.assertTrue(TestTTS.stops > 0);
		Assert.assertFalse(manager.isSpeaking());
	}

	@Test
	public void restartingDuringEarconIgnoresOldCompletion() {
		readComment("old");
		final String oldEarcon = TestTTS.earconIds.get(0);
		manager.readAloud(Arrays.asList(new NativeTTSManager.TTSItem("new", 1, 0)));
		TestTTS.progress.onError(oldEarcon, TextToSpeech.ERROR_OUTPUT);
		idle();
		Assert.assertEquals(Arrays.asList("old", "new"), TestTTS.spoken);
		Assert.assertEquals(0, listener.errors);
	}

	@Test
	public void rejectedSeparatorStopsReadingWithOneError() {
		TestTTS.earconResult = TextToSpeech.ERROR;
		readComment("hello");
		Assert.assertTrue(TestTTS.spoken.isEmpty());
		Assert.assertEquals(1, listener.errors);
		Assert.assertFalse(manager.isSpeaking());
	}

	@Test
	public void longCommentOnlyPlaysOneSeparator() {
		final String text = "a".repeat(4500);
		readComment(text);
		Assert.assertEquals(1, TestTTS.earcons.size());
		Assert.assertEquals(2, TestTTS.spoken.size());
		TestTTS.progress.onDone(TestTTS.ids.get(0));
		idle();
		Assert.assertEquals(1, TestTTS.earcons.size());
		Assert.assertEquals(text, String.join("", TestTTS.spoken));
		Assert.assertEquals(2, TestTTS.spoken.size());
	}

	@Test
	public void lookaheadKeepsAtMostTwoSpeechRequestsSubmitted() {
		read("one", "two", "three", "four");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(Arrays.asList("one", "two"), TestTTS.spoken);
		Assert.assertEquals(Arrays.asList(
				TextToSpeech.QUEUE_FLUSH, TextToSpeech.QUEUE_ADD), TestTTS.queueModes);
		TestTTS.progress.onDone(TestTTS.ids.get(0));
		idle();
		Assert.assertEquals(Arrays.asList("one", "two", "three"), TestTTS.spoken);
		Assert.assertEquals(TextToSpeech.QUEUE_ADD,
				(int) TestTTS.queueModes.get(TestTTS.queueModes.size() - 1));
	}

	@Test
	public void sequentialModeSubmitsOnlyAfterCompletion() {
		setLookahead(false);
		read("one", "two", "three");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(Arrays.asList("one"), TestTTS.spoken);
		TestTTS.progress.onDone(TestTTS.ids.get(0));
		idle();
		Assert.assertEquals(Arrays.asList("one", "two"), TestTTS.spoken);
		Assert.assertEquals(Arrays.asList(
				TextToSpeech.QUEUE_FLUSH, TextToSpeech.QUEUE_ADD), TestTTS.queueModes);
	}

	@Test
	public void highlightingWaitsForSpeechStartInBothModes() {
		boolean needsInitialization = true;
		for (final boolean lookahead : new boolean[] {true, false}) {
			setLookahead(lookahead);
			final int firstIndex = TestTTS.ids.size();
			read("one", "two");
			if (needsInitialization) {
				TestTTS.init(TextToSpeech.SUCCESS);
				needsInitialization = false;
			}
			Assert.assertTrue(listener.positions.isEmpty());
			final String firstId = TestTTS.ids.get(firstIndex);
			TestTTS.progress.onStart(firstId);
			idle();
			Assert.assertTrue(listener.positions.isEmpty());
			TestTTS.progress.onAudioAvailable(firstId, new byte[] {1});
			idle();
			Assert.assertEquals(Arrays.asList(0), listener.positions);
			manager.stop();
			listener.positions.clear();
		}
	}

	@Test
	public void codedCallbackErrorStopsSequentialPlaybackOnce() {
		setLookahead(false);
		read("one", "two");
		TestTTS.init(TextToSpeech.SUCCESS);
		TestTTS.progress.onError(TestTTS.ids.get(0), TextToSpeech.ERROR_OUTPUT);
		idle();
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertEquals(1, listener.errors);
		Assert.assertEquals(Arrays.asList("one"), TestTTS.spoken);
		TestTTS.progress.onError(TestTTS.ids.get(0), TextToSpeech.ERROR_OUTPUT);
		idle();
		Assert.assertEquals(1, listener.errors);
	}

	@Test
	public void cancellationHasNoErrorInBothModes() {
		boolean needsInitialization = true;
		for (final boolean lookahead : new boolean[] {true, false}) {
			setLookahead(lookahead);
			read("one", "two");
			if (needsInitialization) {
				TestTTS.init(TextToSpeech.SUCCESS);
				needsInitialization = false;
			}
			final String active = TestTTS.ids.get(TestTTS.ids.size() - 1);
			manager.stop();
			TestTTS.progress.onStop(active, true);
			idle();
			Assert.assertFalse(manager.isSpeaking());
			Assert.assertEquals(0, listener.errors);
		}
	}

	@Test
	public void earconsAndSpeechShareOneOrderedQueue() {
		manager.readAloud(Arrays.asList(
				new NativeTTSManager.TTSItem("parent", 0, 0),
				new NativeTTSManager.TTSItem("child", 1, 1)));
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(2, TestTTS.earcons.size());
		Assert.assertEquals(Arrays.asList(
				TextToSpeech.QUEUE_FLUSH,
				TextToSpeech.QUEUE_ADD,
				TextToSpeech.QUEUE_ADD,
				TextToSpeech.QUEUE_ADD), TestTTS.queueModes);
		Assert.assertEquals(Arrays.asList(
				"earcon:redreader_synth_tick",
				"speech:parent",
				"earcon:redreader_muted_marimba",
				"speech:child"), TestTTS.events);
	}

	@Test
	public void speedSetBeforeInitializationIsApplied() {
		manager.setSpeed(1.25f);
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(1.25f, TestTTS.speechRate, 0f);
		Assert.assertEquals(1, TestTTS.speechRateCalls);
	}

	@Test
	public void initializationPreservesConfiguredEngineSpeechRate() {
		TestTTS.speechRate = 1.75f;
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(1.75f, TestTTS.speechRate, 0f);
		Assert.assertEquals(0, TestTTS.speechRateCalls);
	}

	@Test
	public void audioBeforeStartHighlightsOnlyAfterStartAndOnlyOnce() {
		read("one", "two");
		TestTTS.init(TextToSpeech.SUCCESS);
		final String first = TestTTS.ids.get(0);
		final String second = TestTTS.ids.get(1);
		TestTTS.progress.onAudioAvailable(first, new byte[] {1});
		TestTTS.progress.onBeginSynthesis(second, 24000, 2, 1);
		idle();
		Assert.assertTrue(listener.positions.isEmpty());
		TestTTS.progress.onStart(first);
		TestTTS.progress.onStart(first);
		TestTTS.progress.onAudioAvailable(first, new byte[] {2});
		idle();
		Assert.assertEquals(Arrays.asList(0), listener.positions);
		manager.stop();
		TestTTS.progress.onStart(second);
		TestTTS.progress.onAudioAvailable(second, new byte[] {3});
		idle();
		Assert.assertEquals(Arrays.asList(0), listener.positions);
	}

	@Test
	public void rejectedUpcomingSubmissionStopsCurrentAndPending() {
		TestTTS.rejectSpeechNumber = 2;
		read("one", "two", "three");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(Arrays.asList("one", "two"), TestTTS.spoken);
		Assert.assertFalse(manager.isSpeaking());
		Assert.assertEquals(1, listener.errors);
		Assert.assertTrue(TestTTS.stops > 0);
	}

	@Test
	public void upcomingCallbackErrorStopsWithoutSubmittingLaterSpeech() {
		read("one", "two", "three");
		TestTTS.init(TextToSpeech.SUCCESS);
		TestTTS.progress.onError(TestTTS.ids.get(1), TextToSpeech.ERROR_SYNTHESIS);
		idle();
		Assert.assertEquals(Arrays.asList("one", "two"), TestTTS.spoken);
		Assert.assertEquals(1, listener.errors);
		Assert.assertFalse(manager.isSpeaking());
	}

	@Test
	public void earconCallbackErrorStopsOnce() {
		readComment("comment");
		TestTTS.progress.onError(TestTTS.earconIds.get(0), TextToSpeech.ERROR_OUTPUT);
		idle();
		Assert.assertEquals(1, listener.errors);
		Assert.assertFalse(manager.isSpeaking());
		TestTTS.progress.onError(TestTTS.earconIds.get(0), TextToSpeech.ERROR_OUTPUT);
		idle();
		Assert.assertEquals(1, listener.errors);
	}

	@Test
	public void selectedVoiceIsPreservedDuringInitialization() {
		TestTTS.selectedVoice = new Voice("chosen", Locale.US,
				Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet());
		read("hello");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertEquals(0, TestTTS.languageCalls);
		Assert.assertEquals(Arrays.asList("hello"), TestTTS.spoken);
	}

	private static void setLookahead(final boolean enabled) {
		General.getSharedPrefs(RuntimeEnvironment.getApplication()).edit()
				.putBoolean("pref_tts_lookahead", enabled).apply();
	}

	private void readComment(final String text) {
		manager.readAloud(Arrays.asList(new NativeTTSManager.TTSItem(text, 0, 0)));
		TestTTS.init(TextToSpeech.SUCCESS);
	}

	@Test
	@Config(sdk = 23)
	public void olderAndroidHighlightsFromPlaybackStartWithoutAudioCallbacks() {
		read("one", "two");
		TestTTS.init(TextToSpeech.SUCCESS);
		Assert.assertTrue(listener.positions.isEmpty());
		TestTTS.progress.onStart(TestTTS.ids.get(0));
		idle();
		Assert.assertEquals(Arrays.asList(0), listener.positions);
	}

	@Test
	public void completedSeparatorCannotFailCurrentSpeechWhenCallbacksAreMissing() {
		readComment("comment");
		final String speech = TestTTS.ids.get(0);
		final String separator = TestTTS.earconIds.get(0);
		TestTTS.progress.onStart(speech);
		TestTTS.progress.onAudioAvailable(speech, new byte[] {1});
		idle();
		TestTTS.progress.onError(separator, TextToSpeech.ERROR_OUTPUT);
		idle();
		Assert.assertTrue(manager.isSpeaking());
		Assert.assertEquals(0, listener.errors);
		Assert.assertEquals(Arrays.asList(0), listener.positions);
	}

	@Test
	public void outOfOrderCompletionDoesNotStallOrSubmitBeyondLookahead() {
		read("one", "two", "three", "four");
		TestTTS.init(TextToSpeech.SUCCESS);
		TestTTS.progress.onDone(TestTTS.ids.get(1));
		idle();
		Assert.assertEquals(2, TestTTS.spoken.size());
		TestTTS.progress.onDone(TestTTS.ids.get(0));
		idle();
		Assert.assertEquals(Arrays.asList("one", "two", "three", "four"), TestTTS.spoken);
		TestTTS.progress.onDone(TestTTS.ids.get(2));
		TestTTS.progress.onDone(TestTTS.ids.get(3));
		idle();
		Assert.assertFalse(manager.isSpeaking());
	}

	@Implements(value = SoundPool.class, callThroughByDefault = false)
	public static class TestSoundPool {
		static SoundPool.OnLoadCompleteListener loadListener;
		static int samples;
		static int plays;
		static int stops;

		static void reset() {
			loadListener = null;
			samples = 0;
			plays = 0;
			stops = 0;
		}

		static void completeLoads(final int status) {
			Assert.assertEquals(5, samples);
			for (int id = 1; id <= samples; id++) {
				loadListener.onLoadComplete(null, id, status);
			}
		}

		@Implementation
		protected void setOnLoadCompleteListener(final SoundPool.OnLoadCompleteListener listener) {
			loadListener = listener;
		}

		@Implementation
		protected int load(final AssetFileDescriptor descriptor, final int priority) {
			return ++samples;
		}

		@Implementation
		protected int play(final int sample, final float left, final float right,
				final int priority, final int loop, final float rate) {
			return ++plays;
		}

		@Implementation
		protected void stop(final int stream) {
			stops++;
		}
	}

	private static class RecordingListener implements NativeTTSManager.Listener {
		int starts;
		int errors;
		int states;
		final List<Integer> positions = new ArrayList<>();

		@Override
		public void onTTSStateChanged(final boolean speaking) {
			states++;
		}

		@Override
		public void onUtteranceStarted(final int position) {
			starts++;
			positions.add(position);
		}

		@Override
		public void onTTSError() {
			errors++;
		}
	}

	@Implements(value = FileProvider.class, callThroughByDefault = true)
	public static class TestFileProvider {
		@Implementation
		protected static Uri getUriForFile(final Context context, final String authority,
				final File file) {
			return new Uri.Builder()
					.scheme("content")
					.authority(authority)
					.appendPath("tts_earcons")
					.appendPath(file.getName())
					.build();
		}
	}

	@Implements(value = TextToSpeech.class, callThroughByDefault = false)
	public static class TestTTS {
		static TextToSpeech.OnInitListener initListener;
		static int language;
		static int speakResult;
		static int earconResult;
		static int shutdowns;
		static int stops;
		static float speechRate;
		static int speechRateCalls;
		static int languageCalls;
		static int speakCalls;
		static int rejectSpeechNumber;
		static int registrationCalls;
		static int registrationFailureNumber;
		static String defaultEngine;
		static Voice selectedVoice;
		static UtteranceProgressListener progress;
		static final List<String> spoken = new ArrayList<>();
		static final List<String> ids = new ArrayList<>();
		static final List<String> earcons = new ArrayList<>();
		static final List<String> registeredEarcons = new ArrayList<>();
		static final List<Uri> registeredUris = new ArrayList<>();
		static final List<String> registeredPackages = new ArrayList<>();
		static final List<Integer> registeredResources = new ArrayList<>();
		static final List<String> earconIds = new ArrayList<>();
		static final List<Integer> queueModes = new ArrayList<>();
		static final List<String> events = new ArrayList<>();

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
			earcons.clear();
			registeredEarcons.clear();
			registeredUris.clear();
			registeredPackages.clear();
			registeredResources.clear();
			earconIds.clear();
			queueModes.clear();
			events.clear();
			speakResult = TextToSpeech.SUCCESS;
			earconResult = TextToSpeech.SUCCESS;
			language = TextToSpeech.LANG_AVAILABLE;
			shutdowns = 0;
			stops = 0;
			speechRate = 0;
			speechRateCalls = 0;
			languageCalls = 0;
			speakCalls = 0;
			rejectSpeechNumber = -1;
			registrationCalls = 0;
			registrationFailureNumber = -1;
			defaultEngine = "com.localtts";
			selectedVoice = null;
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
			languageCalls++;
			return language;
		}

		@Implementation
		protected Voice getVoice() {
			return selectedVoice;
		}

		@Implementation
		protected String getDefaultEngine() {
			return defaultEngine;
		}

		@Implementation
		protected int addEarcon(final String earcon, final Uri uri) {
			registeredEarcons.add(earcon);
			registeredUris.add(uri);
			registrationCalls++;
			return registrationCalls == registrationFailureNumber
					? TextToSpeech.ERROR
					: TextToSpeech.SUCCESS;
		}

		@Implementation
		protected int addEarcon(final String earcon, final String packageName,
				final int resourceId) {
			registeredEarcons.add(earcon);
			registeredPackages.add(packageName);
			registeredResources.add(resourceId);
			registrationCalls++;
			return registrationCalls == registrationFailureNumber
					? TextToSpeech.ERROR
					: TextToSpeech.SUCCESS;
		}

		@Implementation
		protected int playEarcon(final String earcon, final int queueMode,
				final Bundle params, final String id) {
			earcons.add(earcon);
			earconIds.add(id);
			events.add("earcon:" + earcon);
			queueModes.add(queueMode);
			return earconResult;
		}

		@Implementation
		protected int speak(final CharSequence text, final int queueMode,
				final Bundle params, final String id) {
			spoken.add(text.toString());
			ids.add(id);
			events.add("speech:" + text);
			queueModes.add(queueMode);
			speakCalls++;
			return speakCalls == rejectSpeechNumber ? TextToSpeech.ERROR : speakResult;
		}

		@Implementation
		protected int stop() {
			stops++;
			return TextToSpeech.SUCCESS;
		}

		@Implementation
		protected int setSpeechRate(final float rate) {
			speechRateCalls++;
			speechRate = rate;
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
