/*
 * Copyright (C) 2020 The Android Open Source Project
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
 *
 */
package com.google.android.exoplayer2.ui;

import android.graphics.Typeface;
import android.text.Html;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.SparseArray;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.text.span.HorizontalTextInVerticalContextSpan;
import com.google.android.exoplayer2.text.span.RubySpan;
import com.google.android.exoplayer2.text.span.TextEmphasisSpan;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class to convert from <a
 * href="https://developer.android.com/guide/topics/text/spans">span-styled text</a> to HTML.
 *
 * <p>Supports all of the spans used by ExoPlayer's subtitle decoders, including custom ones found
 * in {@link com.google.android.exoplayer2.text.span}.
 */
/* package */ final class SpannedToHtmlConverter {

  // Matches /n and /r/n in ampersand-encoding (returned from Html.escapeHtml).
  private static final Pattern NEWLINE_PATTERN = Pattern.compile("(&#13;)?&#10;");

  private SpannedToHtmlConverter() {}

  /**
   * Convert {@code text} into HTML, adding tags and styling to match any styling spans present.
   *
   * <p>All textual content is HTML-escaped during the conversion.
   *
   * <p>NOTE: The current implementation does not handle overlapping spans correctly, it will
   * generate overlapping HTML tags that are invalid. In most cases this won't be a problem because:
   *
   * <ul>
   *   <li>Most subtitle formats use a tagged structure to carry formatting information (e.g. WebVTT
   *       and TTML), so the {@link Spanned} objects created by these decoders likely won't have
   *       overlapping spans.
   *   <li>WebView/Chromium (the intended destination of this HTML) gracefully handles overlapping
   *       tags and usually renders the same result as spanned text in a TextView.
   * </ul>
   *
   * @param text The (possibly span-styled) text to convert to HTML.
   * @param displayDensity The screen density of the device. WebView treats 1 CSS px as one Android
   *     dp, so to convert size values from Android px to CSS px we need to know the screen density.
   */
  public static HtmlAndCss convert(@Nullable CharSequence text, float displayDensity) {
    if (text == null) {
      return new HtmlAndCss("", /* cssRuleSets= */ ImmutableMap.of());
    }
    if (!(text instanceof Spanned)) {
      return new HtmlAndCss(escapeHtml(text), /* cssRuleSets= */ ImmutableMap.of());
    }
    Spanned spanned = (Spanned) text;

    // Use CSS inheritance to ensure BackgroundColorSpans affect all inner elements
    Set<Integer> backgroundColors = new HashSet<>();
    for (BackgroundColorSpan backgroundColorSpan :
        spanned.getSpans(0, spanned.length(), BackgroundColorSpan.class)) {
      backgroundColors.add(backgroundColorSpan.getBackgroundColor());
    }
    HashMap<String, String> cssRuleSets = new HashMap<>();
    for (int backgroundColor : backgroundColors) {
      cssRuleSets.put(
          HtmlUtils.cssAllClassDescendantsSelector("bg_" + backgroundColor),
          Util.formatInvariant("background-color:%s;", HtmlUtils.toCssRgba(backgroundColor)));
    }

    SparseArray<Transition> spanTransitions = findSpanTransitions(spanned, displayDensity);
    StringBuilder html = new StringBuilder(spanned.length());
    int previousTransition = 0;
    for (int i = 0; i < spanTransitions.size(); i++) {
      int index = spanTransitions.keyAt(i);
      html.append(escapeHtml(spanned.subSequence(previousTransition, index)));

      Transition transition = spanTransitions.get(index);
      Collections.sort(transition.spansRemoved, SpanInfo.FOR_CLOSING_TAGS);
      for (SpanInfo spanInfo : transition.spansRemoved) {
        html.append(spanInfo.closingTag);
      }
      Collections.sort(transition.spansAdded, SpanInfo.FOR_OPENING_TAGS);
      for (SpanInfo spanInfo : transition.spansAdded) {
        html.append(spanInfo.openingTag);
      }
      previousTransition = index;
    }

    html.append(escapeHtml(spanned.subSequence(previousTransition, spanned.length())));

    return new HtmlAndCss(html.toString(), cssRuleSets);
  }

  private static SparseArray<Transition> findSpanTransitions(
      Spanned spanned, float displayDensity) {
    SparseArray<Transition> spanTransitions = new SparseArray<>();

    for (Object span : spanned.getSpans(0, spanned.length(), Object.class)) {
      @Nullable String openingTag = getOpeningTag(span, displayDensity);
      @Nullable String closingTag = getClosingTag(span);
      int spanStart = spanned.getSpanStart(span);
      int spanEnd = spanned.getSpanEnd(span);
      if (openingTag != null) {
        Assertions.checkNotNull(closingTag);
        SpanInfo spanInfo = new SpanInfo(spanStart, spanEnd, openingTag, closingTag);
        getOrCreate(spanTransitions, spanStart).spansAdded.add(spanInfo);
        getOrCreate(spanTransitions, spanEnd).spansRemoved.add(spanInfo);
      }
    }

    return spanTransitions;
  }

  @Nullable
  private static String getOpeningTag(Object span, float displayDensity) {
    if (span instanceof StrikethroughSpan) {
      return "<span style='text-decoration:line-through;'>";
    } else if (span instanceof ForegroundColorSpan) {
      ForegroundColorSpan colorSpan = (ForegroundColorSpan) span;
      return Util.formatInvariant(
          "<span style='color:%s;'>", HtmlUtils.toCssRgba(colorSpan.getForegroundColor()));
    } else if (span instanceof BackgroundColorSpan) {
      BackgroundColorSpan colorSpan = (BackgroundColorSpan) span;
      return Util.formatInvariant("<span class='bg_%s'>", colorSpan.getBackgroundColor());
    } else if (span instanceof HorizontalTextInVerticalContextSpan) {
      return "<span style='text-combine-upright:all;'>";
    } else if (span instanceof AbsoluteSizeSpan) {
      AbsoluteSizeSpan absoluteSizeSpan = (AbsoluteSizeSpan) span;
      float sizeCssPx =
          absoluteSizeSpan.getDip()
              ? absoluteSizeSpan.getSize()
              : absoluteSizeSpan.getSize() / displayDensity;
      return Util.formatInvariant("<span style='font-size:%.2fpx;'>", sizeCssPx);
    } else if (span instanceof RelativeSizeSpan) {
      return Util.formatInvariant(
          "<span style='font-size:%.2f%%;'>", ((RelativeSizeSpan) span).getSizeChange() * 100);
    } else if (span instanceof TypefaceSpan) {
      @Nullable String fontFamily = ((TypefaceSpan) span).getFamily();
      return fontFamily != null
          ? Util.formatInvariant("<span style='font-family:\"%s\";'>", fontFamily)
          : null;
    } else if (span instanceof StyleSpan) {
      switch (((StyleSpan) span).getStyle()) {
        case Typeface.BOLD:
          return "<b>";
        case Typeface.ITALIC:
          return "<i>";
        case Typeface.BOLD_ITALIC:
          return "<b><i>";
        default:
          return null;
      }
    } else if (span instanceof RubySpan) {
      RubySpan rubySpan = (RubySpan) span;
      switch (rubySpan.position) {
        case RubySpan.POSITION_OVER:
          return "<ruby style='ruby-position:over;'>";
        case RubySpan.POSITION_UNDER:
          return "<ruby style='ruby-position:under;'>";
        case RubySpan.POSITION_UNKNOWN:
          return "<ruby style='ruby-position:unset;'>";
        default:
          return null;
      }
    } else if (span instanceof UnderlineSpan) {
      return "<u>";
    } else if (span instanceof TextEmphasisSpan) {
      TextEmphasisSpan textEmphasisSpan = (TextEmphasisSpan) span;
      String style = getTextEmphasisStyle(textEmphasisSpan.mark);
      String position = getTextEmphasisPosition(textEmphasisSpan.position);
      return Util
          .formatInvariant("<span style='-webkit-text-emphasis-style: %s; text-emphasis-style: %s; "
                  + "-webkit-text-emphasis-position: %s; text-emphasis-position: %s;'>",
              style, style, position, position);
    } else {
      return null;
    }
  }

  @Nullable
  private static String getClosingTag(Object span) {
    if (span instanceof StrikethroughSpan
        || span instanceof ForegroundColorSpan
        || span instanceof BackgroundColorSpan
        || span instanceof HorizontalTextInVerticalContextSpan
        || span instanceof AbsoluteSizeSpan
        || span instanceof RelativeSizeSpan
        || span instanceof TextEmphasisSpan) {
      return "</span>";
    } else if (span instanceof TypefaceSpan) {
      @Nullable String fontFamily = ((TypefaceSpan) span).getFamily();
      return fontFamily != null ? "</span>" : null;
    } else if (span instanceof StyleSpan) {
      switch (((StyleSpan) span).getStyle()) {
        case Typeface.BOLD:
          return "</b>";
        case Typeface.ITALIC:
          return "</i>";
        case Typeface.BOLD_ITALIC:
          return "</i></b>";
      }
    } else if (span instanceof RubySpan) {
      RubySpan rubySpan = (RubySpan) span;
      return "<rt>" + escapeHtml(rubySpan.rubyText) + "</rt></ruby>";
    } else if (span instanceof UnderlineSpan) {
      return "</u>";
    }
    return null;
  }

  private static String getTextEmphasisStyle(@TextEmphasisSpan.Mark int mark) {
    switch (mark) {
      case TextEmphasisSpan.MARK_FILLED_CIRCLE:
        return "filled circle";
      case TextEmphasisSpan.MARK_FILLED_DOT:
        return "filled dot";
      case TextEmphasisSpan.MARK_FILLED_SESAME:
        return "filled sesame";
      case TextEmphasisSpan.MARK_OPEN_CIRCLE:
        return "open circle";
      case TextEmphasisSpan.MARK_OPEN_DOT:
        return "open dot";
      case TextEmphasisSpan.MARK_OPEN_SESAME:
        return "open sesame";
      case TextEmphasisSpan.MARK_AUTO: // TODO
        // https://www.w3.org/TR/ttml2/#style-value-emphasis-style
        // If a vertical writing mode applies, then equivalent to filled sesame; otherwise,
        // equivalent to filled circle.
      case TextEmphasisSpan.MARK_UNKNOWN:
      default:
        return "unset";
    }
  }

  private static String getTextEmphasisPosition(@TextEmphasisSpan.Position int position){
    switch (position) {
      case TextEmphasisSpan.POSITION_AFTER:
        return "under left";
      case TextEmphasisSpan.POSITION_UNKNOWN:
      case TextEmphasisSpan.POSITION_BEFORE:
      case TextEmphasisSpan.POSITION_OUTSIDE: /* Not supported, fallback to "before" */
      default:
        // https://www.w3.org/TR/ttml2/#style-value-annotation-position
        // If an implementation does not recognize or otherwise distinguish an annotation position
        // value, then it must be interpreted as if a position of before were specified; as such,
        // an implementation that supports text annotation marks must minimally support the before
        // value.
        return "over right";
    }
  }

  private static Transition getOrCreate(SparseArray<Transition> transitions, int key) {
    @Nullable Transition transition = transitions.get(key);
    if (transition == null) {
      transition = new Transition();
      transitions.put(key, transition);
    }
    return transition;
  }

  private static String escapeHtml(CharSequence text) {
    String escaped = Html.escapeHtml(text);
    return NEWLINE_PATTERN.matcher(escaped).replaceAll("<br>");
  }

  /** Container class for an HTML string and associated CSS rulesets. */
  public static class HtmlAndCss {

    /** A raw HTML string. */
    public final String html;

    /**
     * CSS rulesets used to style {@link #html}.
     *
     * <p>Each key is a CSS selector, and each value is a CSS declaration (i.e. a semi-colon
     * separated list of colon-separated key-value pairs, e.g "prop1:val1;prop2:val2;").
     */
    public final Map<String, String> cssRuleSets;

    private HtmlAndCss(String html, Map<String, String> cssRuleSets) {
      this.html = html;
      this.cssRuleSets = cssRuleSets;
    }
  }

  private static final class SpanInfo {
    /**
     * Sort by end index (descending), then by opening tag and then closing tag (both ascending, for
     * determinism).
     */
    private static final Comparator<SpanInfo> FOR_OPENING_TAGS =
        (info1, info2) -> {
          int result = Integer.compare(info2.end, info1.end);
          if (result != 0) {
            return result;
          }
          result = info1.openingTag.compareTo(info2.openingTag);
          if (result != 0) {
            return result;
          }
          return info1.closingTag.compareTo(info2.closingTag);
        };

    /**
     * Sort by start index (descending), then by opening tag and then closing tag (both descending,
     * for determinism).
     */
    private static final Comparator<SpanInfo> FOR_CLOSING_TAGS =
        (info1, info2) -> {
          int result = Integer.compare(info2.start, info1.start);
          if (result != 0) {
            return result;
          }
          result = info2.openingTag.compareTo(info1.openingTag);
          if (result != 0) {
            return result;
          }
          return info2.closingTag.compareTo(info1.closingTag);
        };

    public final int start;
    public final int end;
    public final String openingTag;
    public final String closingTag;

    private SpanInfo(int start, int end, String openingTag, String closingTag) {
      this.start = start;
      this.end = end;
      this.openingTag = openingTag;
      this.closingTag = closingTag;
    }
  }

  private static final class Transition {
    private final List<SpanInfo> spansAdded;
    private final List<SpanInfo> spansRemoved;

    public Transition() {
      this.spansAdded = new ArrayList<>();
      this.spansRemoved = new ArrayList<>();
    }
  }
}
