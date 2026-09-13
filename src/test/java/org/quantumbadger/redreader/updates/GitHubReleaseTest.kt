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

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubReleaseTest {
	private fun asset(name: String = "RedReader-v1.26.2.apk") = JSONObject()
		.put("name", name).put("state", "uploaded").put("size", 123)
		.put("digest", JSONObject.NULL)

	private fun release(vararg assets: JSONObject) = JSONObject().put("tag_name", "v1.26.2")
		.put("draft", false).put("prerelease", false).put("assets", JSONArray(assets.toList()))

	@Test
	fun parsesReleaseAndOptionalDigest() {
		val json = release(asset())
		val result = GitHubRelease.parse(json.toString(), "1.26.1")
		Assert.assertNotNull(result)
		Assert.assertEquals("1.26.2", result!!.version)
		Assert.assertEquals(123L, result.size)
		Assert.assertNull(result.digest)
		Assert.assertEquals(
			"https://github.com/MarkMad/RedReader/releases/download/v1.26.2/RedReader-v1.26.2.apk",
			result.downloadUrl)
		val digest = "sha256:" + "a".repeat(64)
		Assert.assertEquals(digest, GitHubRelease.parse(
			release(asset().put("digest", digest)).toString(), "1.26.1")!!.digest)
	}

	@Test
	fun comparesNumericVersionsWithoutDowngrades() {
		Assert.assertTrue(GitHubRelease.isNewer("v1.26.10", "1.26.2"))
		Assert.assertTrue(GitHubRelease.isNewer("v1.26.1", "1.26"))
		Assert.assertFalse(GitHubRelease.isNewer("v1.26.2-rc1", "1.26.1"))
		Assert.assertFalse(GitHubRelease.isNewer("v1.26.2", "1.26.2"))
		Assert.assertFalse(GitHubRelease.isNewer("v1.26", "1.26.1"))
		Assert.assertFalse(GitHubRelease.isNewer("999999999999999999999999.1.1", "1.26.1"))
	}

	@Test
	fun rejectsDraftAndPrerelease() {
		for (flag in listOf("draft", "prerelease")) {
			Assert.assertNull(GitHubRelease.parse(release(asset()).put(flag, true).toString(), "1.26.1"))
		}
		Assert.assertNull(GitHubRelease.parse(release(asset())
			.put("tag_name", "v1.26.2-rc1").toString(), "1.26.1"))
	}

	@Test
	fun rejectsAmbiguousAndUnsafeAssets() {
		Assert.assertNull(GitHubRelease.parse(release(asset("a.apk"), asset("b.apk"))
			.toString(), "1.26.1"))
		for (name in listOf("a-debug.apk", "a-unsigned.apk", "../a.apk", "a.apk?x", "a.zip")) {
			Assert.assertNull(name, GitHubRelease.parse(release(asset(name)).toString(), "1.26.1"))
		}
		Assert.assertNotNull(GitHubRelease.parse(release(asset(), asset("debug.apk"),
			asset("notes.txt")).toString(), "1.26.1"))
	}

	@Test
	fun rejectsInvalidSizeDigestAndIncompleteUpload() {
		for (size in listOf(0L, -1L, 200L * 1024 * 1024 + 1)) {
			Assert.assertNull(GitHubRelease.parse(
				release(asset().put("size", size)).toString(), "1.26.1"))
		}
		Assert.assertNull(GitHubRelease.parse(
			release(asset().put("state", "new")).toString(), "1.26.1"))
		Assert.assertNull(GitHubRelease.parse(
			release(asset().put("digest", "sha256:bad")).toString(), "1.26.1"))
		Assert.assertNull(GitHubRelease.parse("broken", "1.26.1"))
	}

	@Suppress("DEPRECATION")
	private fun info(code: Int, version: String, signer: String = "abcd") = PackageInfo().apply {
		packageName = "org.quantumbadger.redreader.fork"
		versionCode = code
		versionName = version
		signatures = arrayOf(Signature(signer))
	}

	@Test
	fun acceptsNewerApkWithMatchingIdentity() {
		UpdateClient.validatePackageInfo(info(119, "1.26.1"), info(120, "1.26.2"),
			"org.quantumbadger.redreader.fork", "1.26.2")
	}

	@Test
	fun rejectsWrongApkIdentityVersionOrSigner() {
		val candidates = listOf(
			info(120, "1.26.2", "aaaa"),
			info(119, "1.26.2"),
			info(118, "1.26.2"),
			info(120, "1.27"),
			info(120, "1.26.2").apply { packageName = "other.app" },
			info(120, "1.26.2").apply { signatures = emptyArray() }
		)
		for (candidate in candidates) {
			Assert.assertThrows(IOException::class.java) {
				UpdateClient.validatePackageInfo(info(119, "1.26.1"), candidate,
					"org.quantumbadger.redreader.fork", "1.26.2")
			}
		}
	}

	@Test
	fun fileProviderOnlySharesUpdateDirectory() {
		val context = RuntimeEnvironment.getApplication()
		val provider = context.packageManager.resolveContentProvider(
			context.packageName + ".updates", PackageManager.GET_META_DATA)!!
		Assert.assertFalse(provider.exported)
		Assert.assertTrue(provider.grantUriPermissions)
		val paths = mutableListOf<String>()
		provider.loadXmlMetaData(context.packageManager, "android.support.FILE_PROVIDER_PATHS").use {
			while (it.next() != XmlPullParser.END_DOCUMENT) {
				if (it.eventType == XmlPullParser.START_TAG && it.name != "paths") {
					paths.add(it.name + ":" + it.getAttributeValue(null, "path"))
				}
			}
		}
		Assert.assertEquals(listOf("cache-path:updates/"), paths)
	}
}
