/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.util.LinkedList;
import java.util.List;

import com.ibm.icu.lang.UScript;
import com.ibm.icu.lang.UScriptRun;

import android.graphics.Paint_Delegate.FontInfo;
import android.graphics.RectF;;

/**
 * Render the text by breaking it into various scripts and using the right font for each script.
 * Can be used to measure the text without actually drawing it.
 */
@SuppressWarnings("deprecation")
public class BidiRenderer {

    /* package */ static class ScriptRun {
        int start;
        int limit;
        boolean isRtl;
        int scriptCode;
        FontInfo font;

        public ScriptRun(int start, int limit, boolean isRtl) {
            this.start = start;
            this.limit = limit;
            this.isRtl = isRtl;
            this.scriptCode = UScript.INVALID_CODE;
        }
    }

    private Graphics2D graphics;
    private Paint_Delegate paint;
    private char[] text;
    // Bounds of the text drawn so far.
    private RectF bounds;

    /**
     * @param graphics May be null.
     * @param paint The Paint to use to get the fonts. Should not be null.
     * @param text Unidirectional text. Should not be null.
     */
    /* package */ BidiRenderer(Graphics2D graphics, Paint_Delegate paint, char[] text) {
        assert (paint != null);
        this.graphics = graphics;
        this.paint = paint;
        this.text = text;
    }

    /**
     * Render unidirectional text.
     *
     * This method can also be used to measure the width of the text without actually drawing it.
     *
     * @param start index of the first character
     * @param limit index of the first character that should not be rendered.
     * @param isRtl is the text right-to-left
     * @param advances If not null, then advances for each character to be rendered are returned
     *            here.
     * @param advancesIndex index into advances from where the advances need to be filled.
     * @param draw If true and {@link graphics} is not null, draw the rendered text on the graphics
     *            at the given co-ordinates
     * @param x The x-coordinate of the left edge of where the text should be drawn on the given
     *            graphics.
     * @param y The y-coordinate at which to draw the text on the given graphics.
     * @return A rectangle specifying the bounds of the text drawn.
     */
    /* package */ RectF renderText(int start, int limit, boolean isRtl, float advances[],
            int advancesIndex, boolean draw, float x, float y) {
        // We break the text into scripts and then select font based on it and then render each of
        // the script runs.
        bounds = new RectF(x, y, x, y);
        for (ScriptRun run : getScriptRuns(text, start, limit, isRtl, paint.getFonts())) {
            int flag = Font.LAYOUT_NO_LIMIT_CONTEXT | Font.LAYOUT_NO_START_CONTEXT;
            flag |= isRtl ? Font.LAYOUT_RIGHT_TO_LEFT : Font.LAYOUT_LEFT_TO_RIGHT;
            renderScript(run.start, run.limit, run.font, flag, advances, advancesIndex, draw);
            advancesIndex += run.limit - run.start;
        }
        return bounds;
    }

    /**
     * Render a script run to the right of the bounds passed. Use the preferred font to render as
     * much as possible. This also implements a fallback mechanism to render characters that cannot
     * be drawn using the preferred font.
     */
    private void renderScript(int start, int limit, FontInfo preferredFont, int flag,
            float advances[], int advancesIndex, boolean draw) {
        List<FontInfo> fonts = paint.getFonts();
        if (fonts == null || preferredFont == null) {
            return;
        }

        while (start < limit) {
            boolean foundFont = false;
            int canDisplayUpTo = preferredFont.mFont.canDisplayUpTo(text, start, limit);
            if (canDisplayUpTo == -1) {
                // We can draw all characters in the text.
                render(start, limit, preferredFont, flag, advances, advancesIndex, draw);
                return;
            } else if (canDisplayUpTo > start) { // can draw something
                render(start, canDisplayUpTo, preferredFont, flag, advances, advancesIndex, draw);
                advancesIndex += canDisplayUpTo - start;
                start = canDisplayUpTo;
            }

            // The current character cannot be drawn with the preferred font. Cycle through all the
            // fonts to check which one can draw it.
            int charCount = Character.isHighSurrogate(text[start]) ? 2 : 1;
            for (FontInfo font : fonts) {
                canDisplayUpTo = font.mFont.canDisplayUpTo(text, start, start + charCount);
                if (canDisplayUpTo == -1) {
                    render(start, start+charCount, font, flag, advances, advancesIndex, draw);
                    start += charCount;
                    advancesIndex += charCount;
                    foundFont = true;
                    break;
                }
            }
            if (!foundFont) {
                // No font can display this char. Use the preferred font. The char will most
                // probably appear as a box or a blank space. We could, probably, use some
                // heuristics and break the character into the base character and diacritics and
                // then draw it, but it's probably not worth the effort.
                render(start, start + charCount, preferredFont, flag, advances, advancesIndex,
                        draw);
                start += charCount;
                advancesIndex += charCount;
            }
        }
    }

    /**
     * Render the text with the given font to the right of the bounds passed.
     */
    private void render(int start, int limit, FontInfo font, int flag, float advances[],
            int advancesIndex, boolean draw) {

        // Since the metrics don't have anti-aliasing set, we create a new FontRenderContext with
        // the anti-aliasing set.
        FontRenderContext f = font.mMetrics.getFontRenderContext();
        FontRenderContext frc = new FontRenderContext(f.getTransform(), paint.isAntiAliased(),
                f.usesFractionalMetrics());
        GlyphVector gv = font.mFont.layoutGlyphVector(frc, text, start, limit, flag);
        int ng = gv.getNumGlyphs();
        int[] ci = gv.getGlyphCharIndices(0, ng, null);
        for (int i = 0; i < ng; i++) {
            float adv = gv.getGlyphMetrics(i).getAdvanceX();
            if (advances != null) {
                int adv_idx = advancesIndex + ci[i];
                advances[adv_idx] += adv;
            }
        }
        if (draw && graphics != null) {
            graphics.drawGlyphVector(gv, bounds.right, bounds.bottom);
        }
        Rectangle2D awtBounds = gv.getVisualBounds();
        RectF visualBounds = awtRectToAndroidRect(awtBounds, bounds.right, bounds.bottom);
        // If the width of the bounds is zero, no text has been drawn yet. Hence, use the
        // coordinates from the bounds as an offset only.
        if (Math.abs(bounds.right - bounds.left) == 0) {
            bounds = visualBounds;
        } else {
            bounds.union(visualBounds);
        }
    }

    private RectF awtRectToAndroidRect(Rectangle2D awtRec, float offsetX, float offsetY) {
        float left = (float) awtRec.getX();
        float top = (float) awtRec.getY();
        float right = (float) (left + awtRec.getWidth());
        float bottom = (float) (top + awtRec.getHeight());
        RectF androidRect = new RectF(left, top, right, bottom);
        androidRect.offset(offsetX, offsetY);
        return androidRect;
    }

    // --- Static helper methods ---

    /* package */  static List<ScriptRun> getScriptRuns(char[] text, int start, int limit,
            boolean isRtl, List<FontInfo> fonts) {
        LinkedList<ScriptRun> scriptRuns = new LinkedList<ScriptRun>();

        int count = limit - start;
        UScriptRun uScriptRun = new UScriptRun(text, start, count);
        while (uScriptRun.next()) {
            int scriptStart = uScriptRun.getScriptStart();
            int scriptLimit = uScriptRun.getScriptLimit();
            ScriptRun run = new ScriptRun(scriptStart, scriptLimit, isRtl);
            run.scriptCode = uScriptRun.getScriptCode();
            setScriptFont(text, run, fonts);
            scriptRuns.add(run);
        }

        return scriptRuns;
    }

    // TODO: Replace this method with one which returns the font based on the scriptCode.
    private static void setScriptFont(char[] text, ScriptRun run,
            List<FontInfo> fonts) {
        for (FontInfo fontInfo : fonts) {
            if (fontInfo.mFont.canDisplayUpTo(text, run.start, run.limit) == -1) {
                run.font = fontInfo;
                return;
            }
        }
        run.font = fonts.get(0);
    }
}
