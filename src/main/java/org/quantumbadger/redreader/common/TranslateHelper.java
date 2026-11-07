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

package org.quantumbadger.redreader.common;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.text.HtmlCompat;

import org.quantumbadger.redreader.R;

import java.util.List;
import java.util.regex.Pattern;

/** Hands text to an installed translation app. */
public final class TranslateHelper {

	/** Translation apps to send the text to directly, in order of preference. */
	private static final String[] PREFERRED_TRANSLATE_PACKAGES = {
			"com.google.android.apps.translate",
			"com.deepl.mobiletranslator",
			"com.microsoft.translator"
	};

	private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\n{3,}");

	private TranslateHelper() {}

	/** Converts Reddit's HTML body into the plain text a reader sees. */
	@NonNull
	public static String htmlToPlainText(@Nullable final String html) {

		if(html == null) {
			return "";
		}

		final String text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
				.toString()
				.replace("￼", "");

		return EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n").trim();
	}

	/** Opens a translation app with the given text. If none is installed, this falls back
	 * to the share sheet so the user can choose another app. */
	public static void translate(@NonNull final Activity activity, @Nullable final String text) {

		if(text == null || text.trim().isEmpty()) {
			General.quickToast(activity, R.string.translate_nothing_to_translate);
			return;
		}

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && tryProcessText(activity, text)) {
			return;
		}

		final Intent share = new Intent(Intent.ACTION_SEND);
		share.setType("text/plain");
		share.putExtra(Intent.EXTRA_TEXT, text);

		try {
			activity.startActivity(Intent.createChooser(
					share,
					activity.getString(R.string.translate_chooser_title)));
		} catch(final ActivityNotFoundException e) {
			General.quickToast(activity, R.string.action_not_handled_by_installed_app_toast);
		}
	}

	@RequiresApi(Build.VERSION_CODES.M)
	private static boolean tryProcessText(
			@NonNull final Activity activity,
			@NonNull final String text) {

		final Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT);
		intent.setType("text/plain");
		intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text);
		intent.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);

		final PackageManager packageManager = activity.getPackageManager();
		final List<ResolveInfo> handlers = packageManager.queryIntentActivities(intent, 0);

		if(handlers.isEmpty()) {
			return false;
		}

		for(final String preferred : PREFERRED_TRANSLATE_PACKAGES) {
			for(final ResolveInfo handler : handlers) {
				if(preferred.equals(handler.activityInfo.packageName)) {
					intent.setClassName(
							handler.activityInfo.packageName,
							handler.activityInfo.name);
					return startActivity(activity, intent);
				}
			}
		}

		return startActivity(activity, Intent.createChooser(
				intent,
				activity.getString(R.string.translate_chooser_title)));
	}

	private static boolean startActivity(
			@NonNull final Activity activity,
			@NonNull final Intent intent) {

		try {
			activity.startActivity(intent);
			return true;
		} catch(final ActivityNotFoundException | SecurityException e) {
			return false;
		}
	}
}
