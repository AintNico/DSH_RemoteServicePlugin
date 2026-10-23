package com.aint.dshremote;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small Markdown renderer for chat bubbles. It covers the subset the agent actually emits
 * (headings, bold, italics, inline code, fenced code, links, lists) without pulling in a
 * rendering library; anything it does not understand is left as plain text.
 */
public final class Markdown {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final int CODE_BG = 0xFFE8EAED;

    private Markdown() {
    }

    public static CharSequence render(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }

        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = source.split("\n", -1);
        boolean codeBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                codeBlock = !codeBlock;
                continue;
            }

            if (codeBlock) {
                int start = out.length();
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.92f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                int level = heading.group(1).length();
                int start = out.length();
                appendInline(out, heading.group(2));
                out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level <= 2 ? 1.18f : 1.08f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                out.append("• ");
                appendInline(out, trimmed.substring(2));
                out.append('\n');
                continue;
            }

            appendInline(out, line);
            out.append('\n');
        }

        // Drop the trailing newline the loop always appends.
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return out.subSequence(0, end);
    }

    /** Inline pass: bold, italics, inline code, links and image placeholders. */
    private static void appendInline(SpannableStringBuilder out, String text) {
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("![", i)) {
                int close = text.indexOf(')', i);
                if (close > 0) {
                    out.append("[图片]");
                    i = close + 1;
                    continue;
                }
            }

            if (text.startsWith("**", i)) {
                int close = text.indexOf("**", i + 2);
                if (close > i) {
                    int start = out.length();
                    appendPlain(out, text.substring(i + 2, close));
                    out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 2;
                    continue;
                }
            }

            if (text.charAt(i) == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i) {
                    int start = out.length();
                    out.append(text, i + 1, close);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }

            if (text.charAt(i) == '[') {
                int mid = text.indexOf("](", i);
                int close = mid > 0 ? text.indexOf(')', mid) : -1;
                if (close > mid) {
                    int start = out.length();
                    String label = text.substring(i + 1, mid);
                    out.append(label);
                    out.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }

            if (text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) != '*') {
                int close = text.indexOf('*', i + 1);
                if (close > i + 1) {
                    int start = out.length();
                    out.append(text, i + 1, close);
                    out.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }

            out.append(text.charAt(i));
            i++;
        }
    }

    /** Nested markup inside bold segments is rare; keep it literal rather than losing text. */
    private static void appendPlain(SpannableStringBuilder out, String text) {
        out.append(text.replace("`", "").replace("*", ""));
    }
}
