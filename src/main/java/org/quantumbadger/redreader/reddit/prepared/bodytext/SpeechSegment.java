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

import androidx.annotation.NonNull;

import org.quantumbadger.redreader.audio.NativeTTSManager;

/** One sentence of displayed body text, and the text to speak for it. */
public final class SpeechSegment implements NativeTTSManager.SpeechHighlight {

	@NonNull private final String mText;
	@NonNull private final BodyElementTextSpanned mElement;
	private final int mStart;
	private final int mEnd;

	SpeechSegment(
			@NonNull final String text,
			@NonNull final BodyElementTextSpanned element,
			final int start,
			final int end) {
		mText = text;
		mElement = element;
		mStart = start;
		mEnd = end;
	}

	@NonNull
	public String getText() {
		return mText;
	}

	@NonNull
	SpeechSegment withPrefix(@NonNull final String prefix) {
		return new SpeechSegment(prefix + mText, mElement, mStart, mEnd);
	}

	@Override
	public void show() {
		mElement.showSpeechHighlight(mStart, mEnd);
	}

	@Override
	public void hide() {
		mElement.hideSpeechHighlight();
	}
}
