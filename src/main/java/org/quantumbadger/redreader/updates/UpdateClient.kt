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

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.quantumbadger.redreader.BuildConfig
import org.quantumbadger.redreader.common.TorCommon
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateClient {
	@Volatile private var call: Call? = null

	private val client = OkHttpClient.Builder()
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.callTimeout(5, TimeUnit.MINUTES)
		.followSslRedirects(false)
		.addNetworkInterceptor { chain ->
			val url = chain.request().url
			if (TorCommon.isTorEnabled() || url.scheme != "https" || url.host !in setOf(
					"api.github.com", "github.com", "release-assets.githubusercontent.com",
					"objects.githubusercontent.com")) {
				throw IOException("Update connection is not allowed")
			}
			chain.proceed(chain.request())
		}
		.build()

	fun cancel() {
		call?.cancel()
	}

	private fun request(url: String): Call {
		if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
		if (TorCommon.isTorEnabled()) throw IOException("Updates are paused while Tor is enabled")
		return client.newCall(Request.Builder().url(url)
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "RedReader/" + BuildConfig.VERSION_NAME)
			.build()).also { call = it }
	}

	fun check(): GitHubRelease? {
		request("https://api.github.com/repos/MarkMad/RedReader/releases/latest").execute().use {
			if (it.code == 404) return null
			if (!it.isSuccessful) throw IOException("GitHub returned HTTP " + it.code)
			val body = it.body ?: throw IOException("Empty release response")
			val source = body.source()
			source.request(1_048_577)
			if (source.buffer.size > 1_048_576) throw IOException("Release response too large")
			return GitHubRelease.parse(source.readUtf8(), BuildConfig.VERSION_NAME)
		}
	}

	fun download(context: Context, release: GitHubRelease, progress: (Int) -> Unit): File {
		val directory = File(context.cacheDir, "updates")
		if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot store update")
		val temporary = File.createTempFile("download-", ".apk", directory)
		try {
			val hash = MessageDigest.getInstance("SHA-256")
			request(release.downloadUrl).execute().use { response ->
				if (!response.isSuccessful) throw IOException("Download failed")
				val body = response.body ?: throw IOException("Empty APK response")
				body.byteStream().use { input ->
					temporary.outputStream().use { output ->
						val buffer = ByteArray(32 * 1024)
						var total = 0L
						var previous = -1
						while (true) {
							if (Thread.currentThread().isInterrupted || TorCommon.isTorEnabled()) {
								throw IOException("Cancelled")
							}
							val count = input.read(buffer)
							if (count < 0) break
							total += count
							if (total > release.size) throw IOException("APK is larger than expected")
							output.write(buffer, 0, count)
							hash.update(buffer, 0, count)
							val percent = (total * 100 / release.size).toInt()
							if (percent != previous) {
								previous = percent
								progress(percent)
							}
						}
						if (total != release.size) throw IOException("Incomplete APK")
					}
				}
			}
			val digest = hash.digest().joinToString("") { "%02x".format(it) }
			if (release.digest != null && !digest.equals(
					release.digest.removePrefix("sha256:"), ignoreCase = true)) {
				throw IOException("APK checksum mismatch")
			}
			validateApk(context, temporary, release.version)
			val result = File(directory, "update.apk")
			if (result.exists() && !result.delete()) throw IOException("Cannot replace old update")
			if (!temporary.renameTo(result)) throw IOException("Cannot save update")
			return result
		} finally {
			temporary.delete()
		}
	}

	companion object {
		@Suppress("DEPRECATION")
		fun validateApk(context: Context, file: File, expectedVersion: String) {
			val manager = context.packageManager
			val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
			val update = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
				?: throw IOException("Invalid APK")
			validatePackageInfo(installed, update, context.packageName, expectedVersion)
		}

		@Suppress("DEPRECATION")
		internal fun validatePackageInfo(installed: PackageInfo, update: PackageInfo,
				packageName: String, expectedVersion: String) {
			if (update.packageName != packageName
				|| update.versionName?.removePrefix("v") != expectedVersion.removePrefix("v")
				|| PackageInfoCompat.getLongVersionCode(update)
					<= PackageInfoCompat.getLongVersionCode(installed)) {
				throw IOException("APK does not contain a newer version of this app")
			}
			val currentSigners = installed.signatures?.map { it.toCharsString() }?.toSet()
			val updateSigners = update.signatures?.map { it.toCharsString() }?.toSet()
			if (currentSigners.isNullOrEmpty() || currentSigners != updateSigners) {
				throw IOException("APK signing key does not match the installed app")
			}
		}
	}
}
