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
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

public class NativeTTSManager {

	private static final String TAG = "NativeTTSManager";
	private static NativeTTSManager sInstance;

	private final Context mContext;
	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private TextToSpeech mTTS;
	private boolean mIsInitialized;
	private boolean mIsSpeaking;
	private long mEngineGeneration;
	private long mNextUtterance;
	private String mActiveUtterance;
	private final Queue<TTSItem> mTextQueue = new ArrayDeque<>();
	private Listener mListener;

	public static class TTSItem {
		public final String text;
		public final int position;

		public TTSItem(final String text, final int position) {
			this.text = text;
			this.position = position;
		}
	}

	public interface Listener {
		void onTTSStateChanged(boolean isSpeaking);
		void onUtteranceStarted(int position);
		void onTTSError();
	}

	private NativeTTSManager(final Context context) {
		mContext = context.getApplicationContext();
	}

	public static synchronized NativeTTSManager getInstance(final Context context) {
		if (sInstance == null) {
			sInstance = new NativeTTSManager(context);
		}
		return sInstance;
	}

	private void initialize() {
		final long generation = ++mEngineGeneration;
		// Defer even a synchronous constructor failure until mTTS has been assigned.
		mTTS = new TextToSpeech(mContext,
				status -> mHandler.post(() -> onInitialized(generation, status)));
		mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
			@Override
			public void onStart(final String utteranceId) {
				mHandler.post(() -> onStarted(utteranceId));
			}

			@Override
			public void onDone(final String utteranceId) {
				mHandler.post(() -> onFinished(utteranceId, false));
			}

			@Override
			public void onError(final String utteranceId) {
				mHandler.post(() -> onFinished(utteranceId, true));
			}
		});
	}

	private synchronized void onInitialized(final long generation, final int status) {
		if (generation != mEngineGeneration || mTTS == null) {
			return;
		}
		if (status == TextToSpeech.SUCCESS && mTTS.setLanguage(Locale.getDefault()) >= 0) {
			mIsInitialized = true;
			playNext();
		} else {
			Log.e(TAG, "TTS initialization or language setup failed (status " + status + ")");
			stop();
			mTTS.shutdown();
			mTTS = null;
			mIsInitialized = false;
			++mEngineGeneration;
			reportError();
		}
	}

	private synchronized void onStarted(final String utteranceId) {
		if (utteranceId.equals(mActiveUtterance) && mListener != null) {
			mListener.onUtteranceStarted(mTextQueue.element().position);
		}
	}

	private synchronized void onFinished(final String utteranceId, final boolean failed) {
		if (!utteranceId.equals(mActiveUtterance)) {
			return;
		}
		mActiveUtterance = null;
		mTextQueue.poll();
		if (failed) {
			reportError();
		}
		playNext();
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

	public synchronized void readAloud(final List<TTSItem> items) {
		stop();
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
				// Keep the UTF-16 halves of supplementary characters together.
				if (end < item.text.length()
						&& Character.isHighSurrogate(item.text.charAt(end - 1))) {
					end--;
				}
				mTextQueue.add(new TTSItem(item.text.substring(start, end),
						start == 0 ? item.position : -1));
				start = end;
			}
		}
		if (mTextQueue.isEmpty()) {
			return;
		}
		// Pending initialization is active playback too, so Stop cancels queued speech.
		mIsSpeaking = true;
		notifyListener();
		if (mTTS == null) {
			initialize();
		} else if (mIsInitialized) {
			playNext();
		}
	}

	private void playNext() {
		while (!mTextQueue.isEmpty()) {
			final TTSItem item = mTextQueue.element();
			mActiveUtterance = Long.toString(++mNextUtterance);
			if (mTTS.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, mActiveUtterance)
					== TextToSpeech.SUCCESS) {
				return;
			}
			// Rejected requests need not produce a callback.
			mActiveUtterance = null;
			mTextQueue.poll();
			reportError();
		}
		mIsSpeaking = false;
		notifyListener();
	}

	public synchronized void stop() {
		// Invalidate callbacks before stopping the engine or replacing the queue.
		mActiveUtterance = null;
		mTextQueue.clear();
		if (mTTS != null) {
			mTTS.stop();
		}
		mIsSpeaking = false;
		notifyListener();
	}

	public synchronized void togglePlayback(final List<TTSItem> itemsIfStarting) {
		if (mIsSpeaking) {
			stop();
		} else {
			readAloud(itemsIfStarting);
		}
	}

	public synchronized void setSpeed(final float speed) {
		if (mTTS != null) {
			mTTS.setSpeechRate(speed);
		}
	}

	public synchronized boolean isSpeaking() {
		return mIsSpeaking;
	}
}
