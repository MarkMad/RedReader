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

package org.quantumbadger.redreader.reddit.prepared.bodytext;

import android.graphics.Rect;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.quantumbadger.redreader.activities.BaseActivity;
import org.quantumbadger.redreader.common.AndroidCommon;
import org.quantumbadger.redreader.common.LinkHandler;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.views.LinkifiedTextView;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class BodyElementTextSpanned extends BodyElement implements DynamicSpanned {

	private static final Set<String> ABBREVIATIONS = new HashSet<>(Arrays.asList(
			"mr", "mrs", "ms", "mx", "dr", "prof", "st", "mt", "rev", "hon", "sgt", "capt",
			"lt", "col", "gen", "gov", "sen", "rep", "pres", "vs", "cf", "fig", "approx"));

	// A single capital such as "F.", except "I.", or dotted letters such as "e.g." or "U.S."
	private static final Pattern INITIALS
			= Pattern.compile("(?:[\\p{Lu}&&[^I]]\\.)|(?:\\p{L}\\.){2,}");

	@NonNull private final SpannableStringBuilder mSpanned;

	private LinkifiedTextView mTextView;

	@NonNull private final ArrayList<Object> mSpeechHighlightSpans = new ArrayList<>();

	public BodyElementTextSpanned(
			@NonNull final BlockType blockType,
			@NonNull final SpannableStringBuilder spanned) {
		super(blockType);
		mSpanned = spanned;
	}

	@Override
	public void addSpanDynamic(final Object what, final int start, final int end, final int flags) {
		AndroidCommon.runOnUiThread(() -> {
			mSpanned.setSpan(what, start, end, flags);
			if(mTextView != null) {
				mTextView.setText(mSpanned);
			}
		});
	}

	@Override
	public void collectSpeechSegments(@NonNull final List<SpeechSegment> destination) {
		final String text = mSpanned.toString();
		final BreakIterator sentences = BreakIterator.getSentenceInstance();

		// Line breaks always end a sentence, even without punctuation
		int lineStart = 0;

		while(lineStart < text.length()) {
			final int newline = text.indexOf('\n', lineStart);
			final int lineEnd = newline < 0 ? text.length() : newline;

			final String line = text.substring(lineStart, lineEnd);
			sentences.setText(line);

			int start = sentences.first();
			int end = sentences.next();

			while(end != BreakIterator.DONE) {
				if(end == line.length() || !endsWithAbbreviation(line, end)) {
					addSpeechSegment(destination, text, lineStart + start, lineStart + end);
					start = end;
				}
				end = sentences.next();
			}

			lineStart = lineEnd + 1;
		}
	}

	/** Whether a sentence break at the given offset follows an abbreviation, not a full stop.
	 * Words which often really do end a sentence, like "etc." or "Inc.", are left out. */
	private static boolean endsWithAbbreviation(
			@NonNull final String line,
			final int sentenceEnd) {

		int wordEnd = sentenceEnd;
		while(wordEnd > 0 && Character.isWhitespace(line.charAt(wordEnd - 1))) {
			wordEnd--;
		}

		int wordStart = wordEnd;
		while(wordStart > 0 && (Character.isLetter(line.charAt(wordStart - 1))
				|| line.charAt(wordStart - 1) == '.')) {
			wordStart--;
		}

		final String word = line.substring(wordStart, wordEnd);

		if(!word.endsWith(".")) {
			return false;
		}

		if(INITIALS.matcher(word).matches()) {
			return true;
		}

		final String lowercase
				= word.substring(0, word.length() - 1).toLowerCase(Locale.ENGLISH);

		if("no".equals(lowercase)) {
			// "No. 5" is an abbreviation, but "I said no. Bob left." is not
			return Character.isDigit(line.charAt(sentenceEnd));
		}

		return ABBREVIATIONS.contains(lowercase);
	}

	private void addSpeechSegment(
			@NonNull final List<SpeechSegment> destination,
			@NonNull final String text,
			final int sentenceStart,
			final int sentenceEnd) {

		int start = sentenceStart;
		int end = sentenceEnd;

		while(start < end && Character.isWhitespace(text.charAt(start))) {
			start++;
		}
		while(end > start && Character.isWhitespace(text.charAt(end - 1))) {
			end--;
		}

		final String spoken = LinkHandler.stripUrls(text.substring(start, end)).trim();

		if(!spoken.isEmpty()) {
			destination.add(new SpeechSegment(spoken, this, start, end));
		}
	}

	void showSpeechHighlight(final int start, final int end) {
		AndroidCommon.runOnUiThread(() -> {
			removeSpeechHighlight();

			final PrefsUtility.TTSHighlightColour colour = PrefsUtility.pref_tts_highlight_colour();
			final PrefsUtility.TTSTextColour textColour = PrefsUtility.pref_tts_text_colour();

			// Without either colour, still scroll to follow the sentence being read
			if(colour != PrefsUtility.TTSHighlightColour.NONE) {
				mSpeechHighlightSpans.add(new BackgroundColorSpan(colour.argb));
			}

			if(textColour != PrefsUtility.TTSTextColour.DEFAULT) {
				mSpeechHighlightSpans.add(new ForegroundColorSpan(textColour.argb));
			}

			if(!mSpeechHighlightSpans.isEmpty()) {
				final int flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;

				for(final Object span : mSpeechHighlightSpans) {
					mSpanned.setSpan(span, start, end, flags);
				}

				// The view holds its own copy of the text. Changing it in place avoids a
				// relayout and a text change accessibility event for every sentence.
				if(mTextView != null) {
					if(mTextView.getText() instanceof Spannable) {
						final Spannable viewText = (Spannable)mTextView.getText();

						for(final Object span : mSpeechHighlightSpans) {
							viewText.setSpan(span, start, end, flags);
						}
					} else {
						mTextView.setText(mSpanned, LinkifiedTextView.BufferType.SPANNABLE);
					}
				}
			}

			if(mTextView == null) {
				return;
			}

			final Layout layout = mTextView.getLayout();

			if(layout != null && mTextView.isShown()) {
				final int line = layout.getLineForOffset(start);
				mTextView.requestRectangleOnScreen(new Rect(
						0,
						layout.getLineTop(line),
						mTextView.getWidth(),
						layout.getLineBottom(line)));
			}
		});
	}

	void hideSpeechHighlight() {
		AndroidCommon.runOnUiThread(this::removeSpeechHighlight);
	}

	private void removeSpeechHighlight() {
		if(mSpeechHighlightSpans.isEmpty()) {
			return;
		}

		for(final Object span : mSpeechHighlightSpans) {
			mSpanned.removeSpan(span);
		}

		if(mTextView != null) {
			if(mTextView.getText() instanceof Spannable) {
				final Spannable viewText = (Spannable)mTextView.getText();

				for(final Object span : mSpeechHighlightSpans) {
					viewText.removeSpan(span);
				}
			} else {
				mTextView.setText(mSpanned, LinkifiedTextView.BufferType.SPANNABLE);
			}
		}

		mSpeechHighlightSpans.clear();
	}

	@Override
	public View generateView(
			@NonNull final BaseActivity activity,
			@Nullable final Integer textColor,
			@Nullable final Float textSize,
			final boolean showLinkButtons) {

		mTextView = new LinkifiedTextView(activity);

		if(textColor != null) {
			mTextView.setTextColor(textColor);
		}
		if(textSize != null) {
			mTextView.setTextSize(textSize);
		}

		mTextView.setText(mSpanned, LinkifiedTextView.BufferType.SPANNABLE);

		if(PrefsUtility.pref_accessibility_separate_body_text_lines()) {

			mTextView.setFocusable(true);
		}

		return mTextView;
	}
}
