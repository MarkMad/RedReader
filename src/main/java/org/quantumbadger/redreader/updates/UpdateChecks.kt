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

package org.quantumbadger.redreader.updates

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.TorCommon
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

object UpdateChecks {
	private val checking = AtomicBoolean(false)

	@JvmStatic
	fun checkAutomatically(activity: AppCompatActivity) {
		val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
		if (!preferences.getBoolean("github_updates_enabled", true) || TorCommon.isTorEnabled()) return
		val now = System.currentTimeMillis()
		val last = preferences.getLong("github_updates_last_check", 0)
		if (now >= last && now - last < 24 * 60 * 60 * 1000L) return
		if (!checking.compareAndSet(false, true)) return
		preferences.edit { putLong("github_updates_last_check", now) }
		val owner = WeakReference(activity)
		Thread {
			try {
				val release = UpdateClient().check()
				if (release != null) Handler(Looper.getMainLooper()).post {
					val screen = owner.get()
					if (screen != null && !screen.isFinishing && !screen.isDestroyed
						&& screen.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
						AlertDialog.Builder(screen)
							.setTitle(R.string.update_title)
							.setMessage(screen.getString(R.string.update_available, release.version))
							.setPositiveButton(R.string.update_view) { _, _ ->
								screen.startActivity(Intent(screen, UpdateActivity::class.java))
							}
							.setNegativeButton(android.R.string.cancel, null)
							.show()
					}
				}
			} catch (_: Exception) {
				// Background checks are silent. Manual checks explain failures and permit retry.
			} finally {
				checking.set(false)
			}
		}.start()
	}
}
