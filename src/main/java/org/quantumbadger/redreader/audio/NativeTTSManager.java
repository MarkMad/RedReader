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
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import androidx.annotation.Nullable;

import org.quantumbadger.redreader.common.General;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

public class NativeTTSManager {

	private static final String TAG = "NativeTTSManager";
	private static final String PREF_TTS_LOOKAHEAD = "pref_tts_lookahead";
	public static final int NO_COMMENT_INDENT = -1;
	private static NativeTTSManager sInstance;

	private final Context mContext;
	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private final Queue<TTSItem> mPendingItems = new ArrayDeque<>();
	private final Set<String> mOutstandingEarcons = new HashSet<>();
	private final Set<String> mStartedSpeech = new HashSet<>();
	private final Set<String> mStartReceived = new HashSet<>();
	private final Set<String> mAudioReceived = new HashSet<>();
	private final Set<String> mAudioLogged = new HashSet<>();
	private final Set<String> mCompletedSpeech = new HashSet<>();
	@Nullable private final String mEnginePackage;
	@Nullable private final DiagnosticsListener mDiagnosticsListener;
	private TextToSpeech mTTS;
	private boolean mIsInitialized;
	private boolean mIsSpeaking;
	private boolean mLookaheadEnabled;
	private boolean mQueueStarted;
	private long mEngineGeneration;
	private long mPlaybackGeneration;
	private long mNextUtterance;
	private float mSpeechRate = 1f;
	private int mPreviousCommentIndent = NO_COMMENT_INDENT;
	private SpeechRequest mCurrentSpeech;
	private SpeechRequest mUpcomingSpeech;
	private Listener mListener;

	private static final class SpeechRequest {
		private final TTSItem mItem;
		private final String mId;
		@Nullable private final String mEarconId;

		private SpeechRequest(
				final TTSItem item,
				final String id,
				@Nullable final String earconId) {
			mItem = item;
			mId = id;
			mEarconId = earconId;
		}
	}

	public static class TTSItem {
		public final String text;
		public final int position;
		public final int commentIndent;

		public TTSItem(final String text, final int position) {
			this(text, position, NO_COMMENT_INDENT);
		}

		public TTSItem(final String text, final int position, final int commentIndent) {
			this.text = text;
			this.position = position;
			this.commentIndent = commentIndent;
		}
	}

	public interface Listener {
		void onTTSStateChanged(boolean isSpeaking);
		void onUtteranceStarted(int position);
		void onTTSError();
	}

	public interface DiagnosticsListener {
		void onTTSEvent(String id, String event, long timeMs, int depth, int code);
	}

	private NativeTTSManager(
			final Context context,
			@Nullable final String enginePackage,
			@Nullable final DiagnosticsListener diagnosticsListener) {
		mContext = context.getApplicationContext();
		mEnginePackage = enginePackage;
		mDiagnosticsListener = diagnosticsListener;
	}

	public static synchronized NativeTTSManager getInstance(final Context context) {
		if (sInstance == null) {
			sInstance = new NativeTTSManager(context, null, null);
		}
		return sInstance;
	}

	/** Creates an isolated manager for an instrumentation test using a specific engine. */
	static NativeTTSManager createForTesting(
			final Context context,
			final String enginePackage,
			final DiagnosticsListener diagnosticsListener) {
		return new NativeTTSManager(context, enginePackage, diagnosticsListener);
	}

	private void initialize() {
		final long generation = ++mEngineGeneration;
		final TextToSpeech.OnInitListener initListener =
				status -> mHandler.post(() -> onInitialized(generation, status));
		// Defer even a synchronous constructor failure until mTTS has been assigned.
		mTTS = mEnginePackage == null
				? new TextToSpeech(mContext, initListener)
				: new TextToSpeech(mContext, initListener, mEnginePackage);
		mTTS.setAudioAttributes(new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.build());
		mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
			@Override
			public void onStart(final String utteranceId) {
				logEvent(utteranceId, "start", 0);
				mHandler.post(() -> onStarted(utteranceId));
			}

			@Override
			public void onDone(final String utteranceId) {
				logEvent(utteranceId, "done", 0);
				mHandler.post(() -> onFinished(utteranceId));
			}

			@Override
			public void onError(final String utteranceId) {
				logEvent(utteranceId, "error", TextToSpeech.ERROR);
				mHandler.post(() -> onFailed(utteranceId, TextToSpeech.ERROR));
			}

			@Override
			public void onError(final String utteranceId, final int errorCode) {
				logEvent(utteranceId, "error", errorCode);
				mHandler.post(() -> onFailed(utteranceId, errorCode));
			}

			@Override
			public void onBeginSynthesis(
					final String utteranceId,
					final int sampleRateInHz,
					final int audioFormat,
					final int channelCount) {
				logEvent(utteranceId, "begin_synthesis", 0);
			}

			@Override
			public void onAudioAvailable(final String utteranceId, final byte[] audio) {
				mHandler.post(() -> onAudioAvailableMain(utteranceId));
			}

			@Override
			public void onStop(final String utteranceId, final boolean interrupted) {
				logEvent(utteranceId, interrupted ? "stop_interrupted" : "stop", 0);
				mHandler.post(() -> onStopped(utteranceId));
			}
		});
	}

	private synchronized void onInitialized(final long generation, final int status) {
		if (generation != mEngineGeneration || mTTS == null) {
			return;
		}
		final Voice selectedVoice = status == TextToSpeech.SUCCESS ? mTTS.getVoice() : null;
		if (status != TextToSpeech.SUCCESS
				|| (selectedVoice == null && mTTS.setLanguage(Locale.getDefault()) < 0)) {
			Log.e(TAG, "TTS initialization or language setup failed (status " + status + ")");
			final boolean reportFailure = mIsSpeaking;
			cancelPlayback();
			mTTS.shutdown();
			mTTS = null;
			mIsInitialized = false;
			++mEngineGeneration;
			if (reportFailure) {
				reportError();
			}
			return;
		}
		mTTS.setSpeechRate(mSpeechRate);
		for (final TTSEarconPlayer.Earcon earcon : TTSEarconPlayer.Earcon.values()) {
			final int result = mTTS.addEarcon(
					earcon.getTtsName(), mContext.getPackageName(), earcon.getRawResourceId());
			if (result != TextToSpeech.SUCCESS) {
				Log.e(TAG, "TTS earcon registration failed (code " + result + ")");
				final boolean reportFailure = mIsSpeaking;
				cancelPlayback();
				mTTS.shutdown();
				mTTS = null;
				mIsInitialized = false;
				++mEngineGeneration;
				if (reportFailure) {
					reportError();
				}
				return;
			}
		}
		mIsInitialized = true;
		fillSpeechQueue();
	}

	private synchronized void onStarted(final String utteranceId) {
		if (!isActiveSpeechId(utteranceId)) {
			return;
		}
		mStartReceived.add(utteranceId);
		maybeHighlight(utteranceId);
	}

	private synchronized void onAudioAvailableMain(final String utteranceId) {
		if (!isActiveSpeechId(utteranceId) || !mAudioLogged.add(utteranceId)) {
			return;
		}
		logEvent(utteranceId, "audio_available", 0);
		mAudioReceived.add(utteranceId);
		maybeHighlight(utteranceId);
	}

	private void maybeHighlight(final String utteranceId) {
		final SpeechRequest started;
		if (mCurrentSpeech != null && mCurrentSpeech.mId.equals(utteranceId)) {
			started = mCurrentSpeech;
		} else if (mUpcomingSpeech != null && mUpcomingSpeech.mId.equals(utteranceId)) {
			started = mUpcomingSpeech;
		} else {
			started = null;
		}
		final boolean playbackStarted = mStartReceived.contains(utteranceId)
				// Some engines report start while still waiting for their first audio buffer.
				&& (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
				|| mAudioReceived.contains(utteranceId));
		if (started != null && playbackStarted && mStartedSpeech.add(utteranceId)) {
			mOutstandingEarcons.remove(started.mEarconId);
			if (mListener != null) {
				mListener.onUtteranceStarted(started.mItem.position);
			}
		}
	}

	private synchronized void onFinished(final String utteranceId) {
		if (mOutstandingEarcons.remove(utteranceId)) {
			return;
		}
		final SpeechRequest finished = getActiveSpeech(utteranceId);
		if (finished != null) {
			mOutstandingEarcons.remove(finished.mEarconId);
		}
		mStartedSpeech.remove(utteranceId);
		mStartReceived.remove(utteranceId);
		mAudioReceived.remove(utteranceId);
		mAudioLogged.remove(utteranceId);
		if (mCurrentSpeech == null || !mCurrentSpeech.mId.equals(utteranceId)) {
			if (mUpcomingSpeech != null && mUpcomingSpeech.mId.equals(utteranceId)) {
				mCompletedSpeech.add(utteranceId);
			}
			return;
		}
		do {
			mCurrentSpeech = mUpcomingSpeech;
			mUpcomingSpeech = null;
		} while (mCurrentSpeech != null && mCompletedSpeech.remove(mCurrentSpeech.mId));
		fillSpeechQueue();
	}

	private synchronized void onFailed(final String utteranceId, final int errorCode) {
		if (isActiveId(utteranceId)) {
			failPlayback(utteranceId, "callback_error", errorCode);
		}
	}

	private synchronized void onStopped(final String utteranceId) {
		if (isActiveId(utteranceId)) {
			cancelPlayback();
			if (mTTS != null) {
				mTTS.stop();
			}
		}
	}

	private boolean isActiveId(final String utteranceId) {
		return utteranceId != null && (utteranceId.equals(speechId(mCurrentSpeech))
				|| utteranceId.equals(speechId(mUpcomingSpeech))
				|| mOutstandingEarcons.contains(utteranceId));
	}

	private boolean isActiveSpeechId(final String utteranceId) {
		return getActiveSpeech(utteranceId) != null;
	}

	@Nullable
	private SpeechRequest getActiveSpeech(final String utteranceId) {
		if (utteranceId == null) {
			return null;
		} else if (mCurrentSpeech != null && mCurrentSpeech.mId.equals(utteranceId)) {
			return mCurrentSpeech;
		} else if (mUpcomingSpeech != null && mUpcomingSpeech.mId.equals(utteranceId)) {
			return mUpcomingSpeech;
		} else {
			return null;
		}
	}

	@Nullable
	private static String speechId(@Nullable final SpeechRequest request) {
		return request == null ? null : request.mId;
	}

	public synchronized void setListener(final Listener listener) {
		mListener = listener;
		notifyListener();
	}

	public synchronized void clearListener(final Listener listener) {
		if (mListener == listener) {
			mListener = null;
			stop();
		}
	}

	private void notifyListener() {
		if (mListener != null) {
			mListener.onTTSStateChanged(mIsSpeaking);
		}
	}

	private void reportError() {
		Log.e(TAG, "Unable to narrate text");
		if (mListener != null) {
			mListener.onTTSError();
		}
	}

	public synchronized void readAloud(@Nullable final List<TTSItem> items) {
		stop();
		mPreviousCommentIndent = NO_COMMENT_INDENT;
		mLookaheadEnabled = General.getSharedPrefs(mContext)
				.getBoolean(PREF_TTS_LOOKAHEAD, true);
		if (items == null) {
			return;
		}
		final int limit = TextToSpeech.getMaxSpeechInputLength();
		for (final TTSItem item : items) {
			if (item == null || item.text == null || item.text.trim().isEmpty()) {
				continue;
			}
			int start = 0;
			while (start < item.text.length()) {
				int end = Math.min(start + limit, item.text.length());
				if (end < item.text.length()
						&& Character.isHighSurrogate(item.text.charAt(end - 1))) {
					end--;
				}
				mPendingItems.add(new TTSItem(
						item.text.substring(start, end),
						start == 0 ? item.position : -1,
						start == 0 ? item.commentIndent : NO_COMMENT_INDENT));
				start = end;
			}
		}
		if (mPendingItems.isEmpty()) {
			return;
		}
		mIsSpeaking = true;
		notifyListener();
		if (mTTS == null) {
			initialize();
		} else if (mIsInitialized) {
			fillSpeechQueue();
		}
	}

	private void fillSpeechQueue() {
		if (mCurrentSpeech == null && !mPendingItems.isEmpty()) {
			mCurrentSpeech = submitSpeech(mPendingItems.poll());
			if (mCurrentSpeech == null) {
				return;
			}
			logEvent(mCurrentSpeech.mId, "submitted", 0);
		}
		if (mLookaheadEnabled && mUpcomingSpeech == null && !mPendingItems.isEmpty()) {
			mUpcomingSpeech = submitSpeech(mPendingItems.poll());
			if (mUpcomingSpeech == null) {
				return;
			}
			logEvent(mUpcomingSpeech.mId, "submitted", 0);
		}
		if (mCurrentSpeech == null && mPendingItems.isEmpty()) {
			mIsSpeaking = false;
			notifyListener();
		}
	}

	@Nullable
	private SpeechRequest submitSpeech(final TTSItem item) {
		String precedingEarconId = null;
		if (item.commentIndent != NO_COMMENT_INDENT) {
			final TTSEarconPlayer.Earcon earcon = selectEarconForComment(
					mPreviousCommentIndent, item.commentIndent);
			mPreviousCommentIndent = item.commentIndent;
			final String earconId = nextId("earcon");
			final int result = mTTS.playEarcon(
					earcon.getTtsName(), nextQueueMode(), null, earconId);
			if (result != TextToSpeech.SUCCESS) {
				failPlayback(earconId, "submit_earcon", result);
				return null;
			}
			mQueueStarted = true;
			mOutstandingEarcons.add(earconId);
			precedingEarconId = earconId;
			logEvent(earconId, "submitted", 0);
		}

		final String speechId = nextId("speech");
		final int result = mTTS.speak(item.text, nextQueueMode(), null, speechId);
		if (result != TextToSpeech.SUCCESS) {
			failPlayback(speechId, "submit_speech", result);
			return null;
		}
		mQueueStarted = true;
		return new SpeechRequest(item, speechId, precedingEarconId);
	}

	private int nextQueueMode() {
		return mQueueStarted ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH;
	}

	private String nextId(final String type) {
		return "tts-" + mPlaybackGeneration + "-" + type + "-" + ++mNextUtterance;
	}

	private void failPlayback(final String id, final String event, final int errorCode) {
		logEvent(id, event, errorCode);
		cancelPlayback();
		if (mTTS != null) {
			mTTS.stop();
		}
		reportError();
	}

	private void logEvent(final String id, final String event, final int code) {
		final long timeMs = SystemClock.elapsedRealtime();
		final int depth;
		synchronized (this) {
			depth = (mCurrentSpeech == null ? 0 : 1) + (mUpcomingSpeech == null ? 0 : 1);
		}
		Log.d(TAG, "id=" + id + " event=" + event + " time=" + timeMs
				+ " depth=" + depth + " code=" + code);
		if (mDiagnosticsListener != null) {
			mDiagnosticsListener.onTTSEvent(id, event, timeMs, depth, code);
		}
	}

	public static TTSEarconPlayer.Earcon selectEarconForComment(
			final int previousIndent,
			final int currentIndent) {
		if (currentIndent == 0) {
			return TTSEarconPlayer.Earcon.SYNTH_TICK;
		} else if (previousIndent == NO_COMMENT_INDENT || currentIndent > previousIndent) {
			return TTSEarconPlayer.Earcon.MUTED_MARIMBA;
		} else if (currentIndent < previousIndent) {
			return TTSEarconPlayer.Earcon.DESCENDING_CHIME;
		} else {
			return TTSEarconPlayer.Earcon.DIGITAL_POP;
		}
	}

	public synchronized void stop() {
		cancelPlayback();
		if (mTTS != null) {
			mTTS.stop();
		}
	}

	private void cancelPlayback() {
		++mPlaybackGeneration;
		mCurrentSpeech = null;
		mUpcomingSpeech = null;
		mPendingItems.clear();
		mOutstandingEarcons.clear();
		mStartedSpeech.clear();
		mStartReceived.clear();
		mAudioReceived.clear();
		mAudioLogged.clear();
		mCompletedSpeech.clear();
		mQueueStarted = false;
		mIsSpeaking = false;
		notifyListener();
	}

	/** Releases the isolated engine created by {@link #createForTesting}. */
	synchronized void shutdownForTesting() {
		stop();
		if (mTTS != null) {
			mTTS.shutdown();
			mTTS = null;
			mIsInitialized = false;
			++mEngineGeneration;
		}
	}

	public synchronized void togglePlayback(final List<TTSItem> itemsIfStarting) {
		if (mIsSpeaking) {
			stop();
		} else {
			readAloud(itemsIfStarting);
		}
	}

	public synchronized void setSpeed(final float speed) {
		mSpeechRate = speed;
		if (mTTS != null) {
			mTTS.setSpeechRate(speed);
		}
	}

	@Nullable
	synchronized Voice getVoiceForTesting() {
		return mTTS == null ? null : mTTS.getVoice();
	}

	public synchronized boolean isSpeaking() {
		return mIsSpeaking;
	}
}
