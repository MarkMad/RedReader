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
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

public class NativeTTSManager implements TextToSpeech.OnInitListener {

	private static final String TAG = "NativeTTSManager";
	private static NativeTTSManager sInstance;

	private final TextToSpeech mTTS;
	private boolean mIsInitialized = false;
	private boolean mIsSpeaking = false;

	private final Queue<TTSItem> mTextQueue = new LinkedList<>();
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
	}

	private NativeTTSManager(final Context context) {
		mTTS = new TextToSpeech(context.getApplicationContext(), this);
		mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
			@Override
			public void onStart(final String utteranceId) {
				mIsSpeaking = true;
				notifyListener();
				try {
					final int pos = Integer.parseInt(utteranceId);
					if (mListener != null) {
						mListener.onUtteranceStarted(pos);
					}
				} catch (final NumberFormatException ignored) {
				}
			}

			@Override
			public void onDone(final String utteranceId) {
				playNext();
			}

			@Override
			public void onError(final String utteranceId) {
				playNext();
			}
		});
	}

	public static synchronized NativeTTSManager getInstance(final Context context) {
		if (sInstance == null) {
			sInstance = new NativeTTSManager(context);
		}
		return sInstance;
	}

	@Override
	public void onInit(final int status) {
		if (status == TextToSpeech.SUCCESS) {
			final int result = mTTS.setLanguage(Locale.getDefault());
			if (result == TextToSpeech.LANG_MISSING_DATA
					|| result == TextToSpeech.LANG_NOT_SUPPORTED) {
				Log.e(TAG, "TTS Language is not supported or missing data");
			} else {
				mIsInitialized = true;
				playNext();
			}
		} else {
			Log.e(TAG, "TTS Initialization failed");
		}
	}

	public void setListener(final Listener listener) {
		mListener = listener;
		notifyListener();
	}

	private void notifyListener() {
		if (mListener != null) {
			mListener.onTTSStateChanged(mIsSpeaking);
		}
	}

	public void readAloud(final List<TTSItem> items) {
		stop();
		if (items == null || items.isEmpty()) {
			return;
		}

		mTextQueue.addAll(items);

		if (mIsInitialized) {
			playNext();
		}
	}

	private void playNext() {
		if (mTextQueue.isEmpty()) {
			mIsSpeaking = false;
			notifyListener();
			return;
		}

		final TTSItem item = mTextQueue.poll();
		if (item != null && item.text != null && !item.text.trim().isEmpty()) {
			mIsSpeaking = true;
			mTTS.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, String.valueOf(item.position));
			notifyListener();
		} else {
			playNext();
		}
	}

	public void stop() {
		mTextQueue.clear();
		if (mTTS != null) {
			mTTS.stop();
		}
		mIsSpeaking = false;
		notifyListener();
	}

	public void togglePlayback(final List<TTSItem> itemsIfStarting) {
		if (mIsSpeaking) {
			stop();
		} else {
			readAloud(itemsIfStarting);
		}
	}

	public void setSpeed(final float speed) {
		if (mTTS != null) {
			mTTS.setSpeechRate(speed);
		}
	}

	public boolean isSpeaking() {
		return mIsSpeaking;
	}
}
