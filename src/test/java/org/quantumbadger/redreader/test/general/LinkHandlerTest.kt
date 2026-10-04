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

package org.quantumbadger.redreader.test.general

import org.junit.Assert.assertEquals
import org.junit.Test
import org.quantumbadger.redreader.common.LinkHandler

class LinkHandlerTest {
	@Test
	fun removesMarkdownLinkWhoseLabelIsAUrl() {
		assertEquals(
			"",
			LinkHandler.stripUrls("[https://giphy.com/gifs/](https://giphy.com/gifs/)")
		)
		assertEquals(
			"...",
			LinkHandler.stripUrls(
				"[https://giphy.com/gifs/](https://giphy.com/gifs/)..."
			)
		)
	}

	@Test
	fun keepsReadableMarkdownLinkLabels() {
		assertEquals(
			"Watch funny GIF, then keep reading.",
			LinkHandler.stripUrls(
				"Watch [funny GIF](https://giphy.com/gifs/example), then keep reading."
			)
		)
	}

	@Test
	fun doesNotConsumeFollowingMarkdownOrProse() {
		assertEquals(
			"and keep me now",
			LinkHandler.stripUrls(
				"[https://one.example/](https://one.example/) and " +
						"[keep me](https://two.example/a_(b)) now"
			)
		)
	}

	@Test
	fun removesImageWithUrlOnlyAltText() {
		assertEquals(
			"Before after",
			LinkHandler.stripUrls(
				"Before ![https://cdn.example/image.gif]" +
						"(https://cdn.example/image_(1).gif) after"
			)
		)
	}

	@Test
	fun preservesNonLinkAndLongMalformedBracketText() {
		assertEquals(
			"Keep [ordinary words] here.",
			LinkHandler.stripUrls("Keep [ordinary words] here.")
		)

		val unmatchedBrackets = "[".repeat(10_000) + "plain prose"
		assertEquals(unmatchedBrackets, LinkHandler.stripUrls(unmatchedBrackets))

		val unmatchedDestinations = "[label](".repeat(2_000) + "plain prose"
		assertEquals(unmatchedDestinations, LinkHandler.stripUrls(unmatchedDestinations))
	}

	@Test
	fun removesMarkdownAutolinksWithoutLeavingAngleBrackets() {
		assertEquals(
			"Before after",
			LinkHandler.stripUrls("Before <https://example.com/path> after")
		)
		val unmatchedAngles = "<".repeat(10_000) + "plain prose"
		assertEquals(unmatchedAngles, LinkHandler.stripUrls(unmatchedAngles))
		val nestedAngles = "<".repeat(10_000) + "plain prose>"
		assertEquals(nestedAngles, LinkHandler.stripUrls(nestedAngles))
	}

	@Test
	fun removesBareUrlsCaseInsensitivelyAndPreservesPunctuation() {
		assertEquals(
			"See, then read this (okay). \uD83D\uDE42",
			LinkHandler.stripUrls(
				"See HTTPS://example.com/a_(b), then read http://other.example/path this " +
						"(okay). http://emoji.example/\uD83D\uDE42"
			)
		)
	}
}
