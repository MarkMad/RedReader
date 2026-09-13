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

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.activities.ViewsBaseActivity
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.TorCommon
import java.io.File
import java.util.concurrent.Executors

class UpdateActivity : ViewsBaseActivity() {
	private val client = UpdateClient()
	private val worker = Executors.newSingleThreadExecutor()
	private lateinit var status: TextView
	private lateinit var action: Button
	private lateinit var progress: ProgressBar
	private var downloadedVersion: String? = null
	private var awaitingPermission = false

	override fun onCreate(savedInstanceState: Bundle?) {
		PrefsUtility.applySettingsTheme(this)
		super.onCreate(savedInstanceState)
		setTitle(R.string.update_title)
		val layout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			val padding = (24 * resources.displayMetrics.density).toInt()
			setPadding(padding, padding, padding, padding)
		}
		status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
		progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
		action = Button(this)
		layout.addView(status)
		layout.addView(progress)
		layout.addView(action)
		setBaseActivityListing(layout)
		downloadedVersion = savedInstanceState?.getString("downloaded_version")
		awaitingPermission = savedInstanceState?.getBoolean("awaiting_permission") ?: false
		if (downloadedVersion != null && apkFile().isFile) readyToInstall() else check()
	}

	private fun apkFile() = File(cacheDir, "updates/update.apk")

	private fun showAction(text: Int, callback: () -> Unit) {
		action.setText(text)
		action.isEnabled = true
		action.visibility = View.VISIBLE
		action.setOnClickListener { callback() }
	}

	private fun busy(message: String) {
		status.text = message
		progress.visibility = View.VISIBLE
		progress.isIndeterminate = true
		action.visibility = View.GONE
	}

	private fun ui(callback: () -> Unit) {
		runOnUiThread {
			if (!isFinishing && !isDestroyed) callback()
		}
	}

	private fun failed() {
		ui {
			progress.visibility = View.GONE
			status.setText(if (TorCommon.isTorEnabled()) R.string.update_tor else R.string.update_error)
			showAction(R.string.update_retry) { check() }
		}
	}

	private fun check() {
		busy(getString(R.string.update_checking))
		worker.execute {
			try {
				val release = client.check()
				ui {
					progress.visibility = View.GONE
					if (release == null) {
						status.setText(R.string.update_current)
						showAction(R.string.update_retry) { check() }
					} else {
						status.text = getString(R.string.update_available, release.version)
						showAction(R.string.update_download) { download(release) }
					}
				}
			} catch (_: Exception) {
				failed()
			}
		}
	}

	private fun download(release: GitHubRelease) {
		busy(getString(R.string.update_downloading))
		progress.isIndeterminate = false
		progress.progress = 0
		worker.execute {
			try {
				client.download(applicationContext, release) { percent ->
					ui { progress.progress = percent }
				}
				ui {
					downloadedVersion = release.version
					readyToInstall()
					install()
				}
			} catch (_: Exception) {
				failed()
			}
		}
	}

	private fun readyToInstall() {
		progress.visibility = View.GONE
		status.setText(R.string.update_ready)
		showAction(R.string.update_install) { install() }
	}

	private fun install() {
		val version = downloadedVersion ?: return
		try {
			UpdateClient.validateApk(this, apkFile(), version)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
				&& !packageManager.canRequestPackageInstalls()) {
				status.setText(R.string.update_permission)
				showAction(R.string.update_allow) {
					try {
						awaitingPermission = true
						startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
							("package:" + packageName).toUri()))
					} catch (_: ActivityNotFoundException) {
						awaitingPermission = false
						failed()
					}
				}
				return
			}
			val uri = FileProvider.getUriForFile(this, packageName + ".updates", apkFile())
			startActivity(Intent(Intent.ACTION_VIEW).apply {
				setDataAndType(uri, "application/vnd.android.package-archive")
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				clipData = ClipData.newRawUri("APK", uri)
			})
		} catch (_: Exception) {
			failed()
		}
	}

	override fun onResume() {
		super.onResume()
		if (awaitingPermission) {
			awaitingPermission = false
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
				|| packageManager.canRequestPackageInstalls()) install()
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		outState.putString("downloaded_version", downloadedVersion)
		outState.putBoolean("awaiting_permission", awaitingPermission)
		super.onSaveInstanceState(outState)
	}

	override fun onDestroy() {
		client.cancel()
		worker.shutdownNow()
		super.onDestroy()
	}
}
