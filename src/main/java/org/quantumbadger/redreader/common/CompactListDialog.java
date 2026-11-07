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
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** A list dialog which is only as wide as its items need, and which scrolls rather than
 * filling the height of the screen. */
public final class CompactListDialog {

	private static final int MIN_WIDTH_DP = 200;
	private static final int MAX_WIDTH_DP = 300;
	private static final float MAX_HEIGHT_FRACTION = 0.6f;

	private CompactListDialog() {}

	@NonNull
	public static AlertDialog show(
			@NonNull final Activity activity,
			@Nullable final CharSequence title,
			@NonNull final CharSequence[] items,
			@NonNull final DialogInterface.OnClickListener listener) {

		final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);

		if(title != null) {
			builder.setTitle(title);
		}

		builder.setItems(items, listener);

		final AlertDialog alert = builder.create();
		alert.setCanceledOnTouchOutside(true);
		alert.show();

		resize(activity, alert);

		return alert;
	}

	private static void resize(@NonNull final Activity activity, @NonNull final AlertDialog alert) {

		final Window window = alert.getWindow();
		final ListView listView = alert.getListView();

		if(window == null || listView == null || listView.getAdapter() == null) {
			return;
		}

		final DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
		final float density = metrics.density;
		final View decorView = window.getDecorView();

		// The Material dialog background is inset from the edges of the window
		final int insetWidth = decorView.getPaddingLeft() + decorView.getPaddingRight();
		final int insetHeight = decorView.getPaddingTop() + decorView.getPaddingBottom();

		final int maxWidth = Math.min(
				Math.round(MAX_WIDTH_DP * density),
				metrics.widthPixels - insetWidth);

		final int minWidth = Math.min(Math.round(MIN_WIDTH_DP * density), maxWidth);

		final int contentWidth = Math.max(
				minWidth,
				Math.min(maxWidth, measureWidestItem(listView)));

		final int windowWidth = contentWidth + insetWidth;

		decorView.measure(
				View.MeasureSpec.makeMeasureSpec(windowWidth, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));

		final int maxWindowHeight
				= Math.round(metrics.heightPixels * MAX_HEIGHT_FRACTION) + insetHeight;

		final int windowHeight = decorView.getMeasuredHeight() > maxWindowHeight
				? maxWindowHeight
				: ViewGroup.LayoutParams.WRAP_CONTENT;

		window.setLayout(windowWidth, windowHeight);
	}

	private static int measureWidestItem(@NonNull final ListView listView) {

		final ListAdapter adapter = listView.getAdapter();
		final int unspecified
				= View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

		int widest = 0;
		View recycled = null;

		for(int i = 0; i < adapter.getCount(); i++) {
			recycled = adapter.getView(i, recycled, listView);
			recycled.measure(unspecified, unspecified);
			widest = Math.max(widest, recycled.getMeasuredWidth());
		}

		return widest + listView.getPaddingLeft() + listView.getPaddingRight();
	}
}
