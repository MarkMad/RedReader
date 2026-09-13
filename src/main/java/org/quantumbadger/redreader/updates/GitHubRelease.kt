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

import org.json.JSONObject

data class GitHubRelease(
	val version: String,
	val downloadUrl: String,
	val size: Long,
	val digest: String?,
) {
	companion object {
		private const val MAX_SIZE = 200L * 1024L * 1024L
		private val VERSION = Regex("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?$")
		private val DIGEST = Regex("^sha256:[0-9a-fA-F]{64}$")

		@JvmStatic
		fun isNewer(candidate: String, current: String): Boolean {
			val c = VERSION.matchEntire(candidate.trim()) ?: return false
			val v = VERSION.matchEntire(current.trim()) ?: return false
			for (i in 1..3) {
				val candidatePart = c.groupValues[i].ifEmpty { "0" }.toLongOrNull() ?: return false
				val currentPart = v.groupValues[i].ifEmpty { "0" }.toLongOrNull() ?: return false
				val comparison = candidatePart.compareTo(currentPart)
				if (comparison != 0) return comparison > 0
			}
			return false
		}

		@JvmStatic
		fun parse(json: String, currentVersion: String): GitHubRelease? = runCatching {
			val release = JSONObject(json)
			if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
			val tag = release.optString("tag_name", "")
			if (!isNewer(tag, currentVersion)) return null
			val assets = release.optJSONArray("assets") ?: return null
			val candidates = (0 until assets.length()).mapNotNull { index ->
				val asset = assets.optJSONObject(index) ?: return@mapNotNull null
				if (asset.optString("state", "") != "uploaded") return@mapNotNull null
				val name = asset.optString("name", "")
				if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk", RegexOption.IGNORE_CASE)
						.matches(name)) return@mapNotNull null
				val lower = name.lowercase()
				if (!lower.endsWith(".apk") || lower.contains("debug") || lower.contains("unsigned")) {
					return@mapNotNull null
				}
				val size = asset.optLong("size", -1L)
				if (size <= 0 || size > MAX_SIZE) return@mapNotNull null
				val digestValue = if (asset.isNull("digest")) null
					else asset.optString("digest", "").ifEmpty { null }
				if (digestValue != null && !DIGEST.matches(digestValue)) return@mapNotNull null
				Triple(name, size, digestValue)
			}
			if (candidates.size != 1) return null
			val (name, size, digest) = candidates.single()
			GitHubRelease(tag.removePrefix("v"),
				"https://github.com/MarkMad/RedReader/releases/download/$tag/$name", size, digest)
		}.getOrNull()
	}
}
