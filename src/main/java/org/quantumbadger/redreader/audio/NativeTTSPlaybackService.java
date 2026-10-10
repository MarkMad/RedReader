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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Notification.MediaStyle;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.quantumbadger.redreader.R;
import org.quantumbadger.redreader.activities.MainActivity;

public final class NativeTTSPlaybackService extends Service {

	private static final String CHANNEL_ID = "native_tts_playback";
	private static final int NOTIFICATION_ID = 4102;
	private static final String ACTION_UPDATE = "org.quantumbadger.redreader.tts.UPDATE";
	private static final String ACTION_PLAY_PAUSE = "org.quantumbadger.redreader.tts.PLAY_PAUSE";
	private static final String ACTION_PREVIOUS = "org.quantumbadger.redreader.tts.PREVIOUS";
	private static final String ACTION_NEXT = "org.quantumbadger.redreader.tts.NEXT";
	private static final String ACTION_STOP = "org.quantumbadger.redreader.tts.STOP";

	private NativeTTSManager mManager;
	private MediaSession mMediaSession;

	public static void updatePlayback(
			final Context context,
			final boolean active) {
		final Context appContext = context.getApplicationContext();
		final Intent intent = new Intent(appContext, NativeTTSPlaybackService.class);
		if (!active) {
			appContext.stopService(intent);
			return;
		}
		intent.setAction(ACTION_UPDATE);
		ContextCompat.startForegroundService(appContext, intent);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		mManager = NativeTTSManager.getInstance(this);
		createNotificationChannel();
		mMediaSession = new MediaSession(this, "RedReader read aloud");
		mMediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
				| MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
		mMediaSession.setCallback(new MediaSession.Callback() {
			@Override
			public void onPlay() {
				handleAction(ACTION_PLAY_PAUSE);
			}

			@Override
			public void onPause() {
				if (mManager.isSpeaking()) {
					handleAction(ACTION_PLAY_PAUSE);
				}
				super.onPause();
			}

			@Override
			public void onSkipToPrevious() {
				handleAction(ACTION_PREVIOUS);
			}

			@Override
			public void onSkipToNext() {
				handleAction(ACTION_NEXT);
			}

			@Override
			public void onStop() {
				handleAction(ACTION_STOP);
				super.onStop();
			}
		});
		mMediaSession.setMetadata(new MediaMetadata.Builder()
				.putString(MediaMetadata.METADATA_KEY_TITLE,
						getString(R.string.tts_notification_title))
				.putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
				.build());
	}

	@Nullable
	@Override
	public IBinder onBind(final Intent intent) {
		return null;
	}

	@Override
	public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
		if (intent == null) {
			stopPlaybackService();
			return START_NOT_STICKY;
		}
		if (ACTION_UPDATE.equals(intent.getAction())) {
			updateSessionAndNotification();
		} else {
			handleAction(intent.getAction());
		}
		return START_NOT_STICKY;
	}

	private void handleAction(@Nullable final String action) {
		if (ACTION_PLAY_PAUSE.equals(action)) {
			if (mManager.isSpeaking()) {
				mManager.pause();
			} else if (mManager.isPaused()) {
				mManager.resume();
			}
		} else if (ACTION_PREVIOUS.equals(action)) {
			mManager.skipToPreviousComment();
		} else if (ACTION_NEXT.equals(action)) {
			mManager.skipToNextComment();
		} else if (ACTION_STOP.equals(action)) {
			mManager.stop();
			stopPlaybackService();
			return;
		} else {
			stopPlaybackService();
			return;
		}
		updateSessionAndNotification();
	}

	private void updateSessionAndNotification() {
		final boolean speaking = mManager.isSpeaking();
		final boolean paused = mManager.isPaused();
		if (!speaking && !paused) {
			stopPlaybackService();
			return;
		}
		final long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
				| PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_PREVIOUS
				| PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_STOP;
		final PlaybackState state = new PlaybackState.Builder()
				.setActions(actions)
				.setState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING,
						PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
				.build();
		mMediaSession.setPlaybackState(state);
		mMediaSession.setActive(true);
		final Notification notification = createNotification(paused);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(NOTIFICATION_ID, notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
		} else {
			startForeground(NOTIFICATION_ID, notification);
		}
	}

	private Notification createNotification(final boolean paused) {
		final int playPauseIcon = paused
				? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause;
		final int playPauseTitle = paused
				? R.string.tts_notification_resume : R.string.tts_notification_pause;
		final Intent openApp = new Intent(this, MainActivity.class)
				.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		final PendingIntent contentIntent = PendingIntent.getActivity(
				this, 0, openApp, pendingIntentFlags());
		final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				? new Notification.Builder(this, CHANNEL_ID)
				: new Notification.Builder(this);
		return builder
				.setSmallIcon(R.drawable.icon_notif)
				.setContentTitle(getString(R.string.tts_notification_title))
				.setContentText(getString(paused
						? R.string.tts_notification_paused : R.string.tts_notification_playing))
				.setContentIntent(contentIntent)
				.setCategory(Notification.CATEGORY_TRANSPORT)
				.setVisibility(Notification.VISIBILITY_PUBLIC)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.addAction(action(android.R.drawable.ic_media_previous,
						R.string.tts_notification_previous, ACTION_PREVIOUS, 1))
				.addAction(action(playPauseIcon, playPauseTitle, ACTION_PLAY_PAUSE, 2))
				.addAction(action(android.R.drawable.ic_media_next,
						R.string.tts_notification_next, ACTION_NEXT, 3))
				.addAction(action(android.R.drawable.ic_menu_close_clear_cancel,
						R.string.tts_notification_stop, ACTION_STOP, 4))
				.setStyle(new MediaStyle()
						.setMediaSession(mMediaSession.getSessionToken())
						.setShowActionsInCompactView(0, 1, 2))
				.build();
	}

	private Notification.Action action(
			final int icon,
			final int title,
			final String action,
			final int requestCode) {
		final Intent intent = new Intent(this, NativeTTSPlaybackService.class).setAction(action);
		final PendingIntent pendingIntent = PendingIntent.getService(
				this, requestCode, intent, pendingIntentFlags());
		final Notification.Action.Builder builder = new Notification.Action.Builder(
				icon,
				getString(title),
				pendingIntent);
		return builder.build();
	}

	private int pendingIntentFlags() {
		return PendingIntent.FLAG_UPDATE_CURRENT
				| (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
				? PendingIntent.FLAG_IMMUTABLE
				: 0);
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			final NotificationChannel channel = new NotificationChannel(
					CHANNEL_ID,
					getString(R.string.tts_notification_channel),
					NotificationManager.IMPORTANCE_LOW);
			channel.setShowBadge(false);
			getSystemService(NotificationManager.class).createNotificationChannel(channel);
		}
	}

	@Override
	public void onDestroy() {
		stopForeground(true);
		mMediaSession.setActive(false);
		mMediaSession.release();
		super.onDestroy();
	}

	private void stopPlaybackService() {
		if (mMediaSession != null) {
			mMediaSession.setActive(false);
		}
		stopForeground(true);
		stopSelf();
	}
}