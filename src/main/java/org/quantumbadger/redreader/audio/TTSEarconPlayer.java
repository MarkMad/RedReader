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
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;

public final class TTSEarconPlayer {

	private static final String TAG = "TTSEarconPlayer";

	public enum Earcon {
		WOODEN_CLICK("Sounds/wooden_click.wav", 120),
		DESCENDING_CHIME("Sounds/descending_chime.wav", 240),
		DIGITAL_POP("Sounds/digital_pop.wav", 140),
		MUTED_MARIMBA("Sounds/muted_marimba.wav", 220),
		SYNTH_TICK("Sounds/synth_tick.wav", 100);

		private final String mAssetPath;
		private final int mDurationMs;

		Earcon(final String assetPath, final int durationMs) {
			mAssetPath = assetPath;
			mDurationMs = durationMs;
		}
	}

	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private final SoundPool mSoundPool;
	private final EnumMap<Earcon, Integer> mSampleIds = new EnumMap<>(Earcon.class);
	private final Set<Integer> mLoadedSamples = new HashSet<>();
	private PendingPlayback mPendingPlayback;
	private int mStreamId;
	private int mLoadsRemaining = Earcon.values().length;
	private long mGeneration;

	private static final class PendingPlayback {
		private final Earcon mEarcon;
		private final Runnable mOnComplete;
		private final long mGeneration;

		private PendingPlayback(
				final Earcon earcon,
				final Runnable onComplete,
				final long generation) {
			mEarcon = earcon;
			mOnComplete = onComplete;
			mGeneration = generation;
		}
	}

	public TTSEarconPlayer(final Context context) {
		final AudioAttributes attributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build();
		mSoundPool = new SoundPool.Builder()
				.setMaxStreams(1)
				.setAudioAttributes(attributes)
				.build();
		mSoundPool.setOnLoadCompleteListener((pool, sampleId, status) ->
				mHandler.post(() -> onLoadComplete(sampleId, status)));
		for (final Earcon earcon : Earcon.values()) {
			try (AssetFileDescriptor asset = context.getAssets().openFd(earcon.mAssetPath)) {
				final int sampleId = mSoundPool.load(asset, 1);
				if (sampleId != 0) {
					mSampleIds.put(earcon, sampleId);
				} else {
					mLoadsRemaining--;
				}
			} catch (final IOException e) {
				Log.e(TAG, "Failed to open " + earcon.mAssetPath, e);
				mLoadsRemaining--;
			}
		}
	}

	public void play(final Earcon earcon, final Runnable onComplete) {
		mHandler.post(() -> {
			stopOnHandler();
			mPendingPlayback = new PendingPlayback(earcon, onComplete, mGeneration);
			playPendingIfLoaded();
		});
	}

	public void stop() {
		mHandler.post(this::stopOnHandler);
	}

	private void stopOnHandler() {
		mGeneration++;
		mPendingPlayback = null;
		if (mStreamId != 0) {
			mSoundPool.stop(mStreamId);
			mStreamId = 0;
		}
	}

	private void onLoadComplete(final int sampleId, final int status) {
		mLoadsRemaining--;
		if (status == 0) {
			mLoadedSamples.add(sampleId);
		} else {
			Log.e(TAG, "Failed to load earcon sample " + sampleId);
		}
		playPendingIfLoaded();
	}

	private void playPendingIfLoaded() {
		final PendingPlayback playback = mPendingPlayback;
		if (playback == null) {
			return;
		}
		final Integer sampleId = mSampleIds.get(playback.mEarcon);
		if (sampleId != null && !mLoadedSamples.contains(sampleId) && mLoadsRemaining > 0) {
			return;
		}
		mPendingPlayback = null;
		if (sampleId != null && mLoadedSamples.contains(sampleId)) {
			mStreamId = mSoundPool.play(sampleId, 1f, 1f, 1, 0, 1f);
		}
		mHandler.postDelayed(() -> {
			if (playback.mGeneration == mGeneration) {
				mStreamId = 0;
				playback.mOnComplete.run();
			}
		}, playback.mEarcon.mDurationMs);
	}

	/**
	 * Test helper that plays all candidate comment separators in order.
	 */
	public static void playAllForTesting(final Context context) {
		final TTSEarconPlayer player = new TTSEarconPlayer(context);
		final Earcon[] earcons = Earcon.values();
		playForTesting(player, earcons, 0);
	}

	private static void playForTesting(
			final TTSEarconPlayer player,
			final Earcon[] earcons,
			final int index) {
		if (index < earcons.length) {
			player.play(earcons[index], () -> playForTesting(player, earcons, index + 1));
		} else {
			player.mSoundPool.release();
		}
	}
}
