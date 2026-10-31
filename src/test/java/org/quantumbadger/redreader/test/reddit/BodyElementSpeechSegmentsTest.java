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

package org.quantumbadger.redreader.test.reddit;

import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.quantumbadger.redreader.reddit.prepared.bodytext.BlockType;
import org.quantumbadger.redreader.reddit.prepared.bodytext.BodyElement;
import org.quantumbadger.redreader.reddit.prepared.bodytext.BodyElementNumberedListElement;
import org.quantumbadger.redreader.reddit.prepared.bodytext.BodyElementTextSpanned;
import org.quantumbadger.redreader.reddit.prepared.bodytext.BodyElementVerticalSequence;
import org.quantumbadger.redreader.reddit.prepared.bodytext.SpeechSegment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BodyElementSpeechSegmentsTest {

	private static BodyElementTextSpanned textElement(final SpannableStringBuilder text) {
		return new BodyElementTextSpanned(BlockType.NORMAL_TEXT, text);
	}

	private static List<String> spokenText(final BodyElement element) {
		final List<SpeechSegment> segments = new ArrayList<>();
		element.collectSpeechSegments(segments);
		final List<String> result = new ArrayList<>();
		for(final SpeechSegment segment : segments) {
			result.add(segment.getText());
		}
		return result;
	}

	private static String highlighted(final SpannableStringBuilder text) {
		final BackgroundColorSpan[] spans
				= text.getSpans(0, text.length(), BackgroundColorSpan.class);
		Assert.assertEquals(1, spans.length);
		return text.subSequence(text.getSpanStart(spans[0]), text.getSpanEnd(spans[0]))
				.toString();
	}

	@Test
	public void splitsTextIntoSentences() {
		Assert.assertEquals(
				Arrays.asList("First one.", "Second one?", "Third"),
				spokenText(textElement(new SpannableStringBuilder(
						"First one. Second one?\nThird\n"))));
	}

	@Test
	public void abbreviationsDoNotEndSentences() {
		for(final String sentence : Arrays.asList(
				"Ask Dr. Smith about it.",
				"It was Mr. Jones vs. The World.",
				"Use a tool, e.g. Python or Kotlin.",
				"The U.S. Senate voted.",
				"John F. Kennedy spoke.",
				"See No. 5 on the list.")) {
			Assert.assertEquals(
					Arrays.asList(sentence, "Next one."),
					spokenText(textElement(new SpannableStringBuilder(
							sentence + " Next one."))));
		}
	}

	@Test
	public void wordsResemblingAbbreviationsStillEndSentences() {
		Assert.assertEquals(
				Arrays.asList("So did I.", "Then we left."),
				spokenText(textElement(new SpannableStringBuilder(
						"So did I. Then we left."))));
		Assert.assertEquals(
				Arrays.asList("I said no.", "Bob left."),
				spokenText(textElement(new SpannableStringBuilder(
						"I said no. Bob left."))));
		Assert.assertEquals(
				Arrays.asList("Apples, pears, etc.", "Then more."),
				spokenText(textElement(new SpannableStringBuilder(
						"Apples, pears, etc. Then more."))));
	}

	@Test
	public void lineBreaksEndSentencesWithoutPunctuation() {
		Assert.assertEquals(
				Arrays.asList("first line", "second line"),
				spokenText(textElement(new SpannableStringBuilder(
						"first line\nsecond line"))));
	}

	@Test
	public void urlsAreHighlightedButNotSpoken() {
		final SpannableStringBuilder text
				= new SpannableStringBuilder("Look at https://example.com/a now.\nhttps://b.com");
		final BodyElementTextSpanned element = textElement(text);
		final List<SpeechSegment> segments = new ArrayList<>();
		element.collectSpeechSegments(segments);

		Assert.assertEquals(1, segments.size());
		Assert.assertFalse(segments.get(0).getText().contains("example.com"));

		segments.get(0).show();
		Assert.assertEquals("Look at https://example.com/a now.", highlighted(text));
	}

	@Test
	public void highlightsOnlyTheShownSentenceAndHidesCleanly() {
		final SpannableStringBuilder text = new SpannableStringBuilder("One here. Two here.");
		final List<SpeechSegment> segments = new ArrayList<>();
		textElement(text).collectSpeechSegments(segments);

		segments.get(0).show();
		Assert.assertEquals("One here.", highlighted(text));

		segments.get(0).hide();
		segments.get(1).show();
		Assert.assertEquals("Two here.", highlighted(text));

		segments.get(1).hide();
		Assert.assertEquals(
				0,
				text.getSpans(0, text.length(), BackgroundColorSpan.class).length);
	}

	@Test
	public void collectsNestedElementsInOrderAndSpeaksListNumbers() {
		final ArrayList<BodyElement> item = new ArrayList<>();
		item.add(textElement(new SpannableStringBuilder("Item text. More text.")));

		final ArrayList<BodyElement> elements = new ArrayList<>();
		elements.add(textElement(new SpannableStringBuilder("Intro.")));
		elements.add(new BodyElementNumberedListElement(2, item));

		Assert.assertEquals(
				Arrays.asList("Intro.", "2. Item text.", "More text."),
				spokenText(new BodyElementVerticalSequence(elements)));
	}
}
