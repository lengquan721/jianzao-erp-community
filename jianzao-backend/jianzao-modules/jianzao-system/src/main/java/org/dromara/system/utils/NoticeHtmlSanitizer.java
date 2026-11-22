package org.dromara.system.utils;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 公告富文本白名单净化器。只保留常见排版、链接和图片；不依赖浏览器端净化。
 */
public final class NoticeHtmlSanitizer {

    private static final Set<String> ALLOWED_TAGS = Set.of(
        "p", "br", "hr", "strong", "b", "em", "i", "u", "s", "strike",
        "blockquote", "pre", "code", "ol", "ul", "li", "h1", "h2", "h3", "h4", "h5", "h6",
        "a", "img", "span", "div", "sub", "sup"
    );
    private static final Set<String> DROP_CONTENT_TAGS = Set.of(
        "script", "style", "iframe", "object", "embed", "svg", "math", "template", "textarea", "noscript"
    );
    private static final Set<String> VOID_TAGS = Set.of("br", "hr", "img");
    private static final Pattern SAFE_DIMENSION = Pattern.compile("[1-9][0-9]{0,3}");
    private static final Pattern SAFE_HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{3,8}");
    private static final Pattern SAFE_COLOR_FUNCTION = Pattern.compile("(?i)(?:rgb|rgba|hsl|hsla)\\([0-9.%\\s,/+-]{1,64}\\)");
    private static final Set<String> NAMED_COLORS = Set.of(
        "black", "white", "red", "green", "blue", "yellow", "orange", "purple", "gray", "grey", "silver", "navy", "teal", "maroon", "olive", "lime", "aqua", "fuchsia", "transparent"
    );

    private NoticeHtmlSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        StringBuilder output = new StringBuilder(Math.min(html.length(), 8192));
        try {
            class SanitizingCallback extends HTMLEditorKit.ParserCallback {
                private final Deque<String> openTags = new ArrayDeque<>();
                private final Deque<String> suppressedTags = new ArrayDeque<>();

                @Override
                public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                    String name = tag.toString().toLowerCase(Locale.ROOT);
                    if (!suppressedTags.isEmpty()) {
                        suppressedTags.push(name);
                        return;
                    }
                    if (DROP_CONTENT_TAGS.contains(name)) {
                        suppressedTags.push(name);
                        return;
                    }
                    if (!ALLOWED_TAGS.contains(name)) {
                        return;
                    }
                    output.append('<').append(name);
                    appendAttributes(output, name, attributes);
                    output.append('>');
                    if (!VOID_TAGS.contains(name)) {
                        openTags.push(name);
                    }
                }

                @Override
                public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                    String name = tag.toString().toLowerCase(Locale.ROOT);
                    if (!suppressedTags.isEmpty() || DROP_CONTENT_TAGS.contains(name) || !ALLOWED_TAGS.contains(name)) {
                        return;
                    }
                    output.append('<').append(name);
                    appendAttributes(output, name, attributes);
                    output.append('>');
                    if (!VOID_TAGS.contains(name)) {
                        output.append("</").append(name).append('>');
                    }
                }

                @Override
                public void handleEndTag(HTML.Tag tag, int position) {
                    String name = tag.toString().toLowerCase(Locale.ROOT);
                    if (!suppressedTags.isEmpty()) {
                        if (suppressedTags.contains(name)) {
                            while (!suppressedTags.isEmpty()) {
                                if (suppressedTags.pop().equals(name)) break;
                            }
                        }
                        return;
                    }
                    if (!ALLOWED_TAGS.contains(name) || VOID_TAGS.contains(name) || !openTags.contains(name)) {
                        return;
                    }
                    while (!openTags.isEmpty()) {
                        String opened = openTags.pop();
                        output.append("</").append(opened).append('>');
                        if (opened.equals(name)) {
                            break;
                        }
                    }
                }

                @Override
                public void handleText(char[] data, int position) {
                    if (suppressedTags.isEmpty()) {
                        appendEscaped(output, new String(data));
                    }
                }

                @Override
                public void handleComment(char[] data, int position) {
                    // 注释不参与公告渲染。
                }

                @Override
                public void handleEndOfLineString(String eol) {
                    // 由 ParserDelegator 管理行结束符。
                }

                void finish() {
                    while (!openTags.isEmpty()) {
                        output.append("</").append(openTags.pop()).append('>');
                    }
                }
            }
            SanitizingCallback callback = new SanitizingCallback();
            new ParserDelegator().parse(new StringReader(html), callback, true);
            callback.finish();
        } catch (IOException | RuntimeException exception) {
            // 解析异常时安全失败为纯文本，而不是回退到原始 HTML。
            output.setLength(0);
            appendEscaped(output, html);
        }
        return output.toString();
    }

    private static void appendAttributes(StringBuilder output, String tag, MutableAttributeSet attributes) {
        String href = null;
        String src = null;
        String target = null;
        String rel = null;
        String alt = null;
        String title = null;
        String width = null;
        String height = null;
        String classes = null;
        String style = null;
        Enumeration<?> names = attributes.getAttributeNames();
        while (names.hasMoreElements()) {
            Object key = names.nextElement();
            String name = key.toString().toLowerCase(Locale.ROOT);
            Object rawValue = attributes.getAttribute(key);
            if (rawValue == null || rawValue instanceof HTML.Tag) {
                continue;
            }
            String value = rawValue.toString().trim();
            switch (name) {
                case "href" -> href = value;
                case "src" -> src = value;
                case "target" -> target = value;
                case "rel" -> rel = value;
                case "alt" -> alt = value;
                case "title" -> title = value;
                case "width" -> width = value;
                case "height" -> height = value;
                case "class" -> classes = value;
                case "style" -> style = value;
                default -> { /* 所有事件处理器、data-* 与其它属性默认丢弃。 */ }
            }
        }
        if ("a".equals(tag) && isSafeUrl(href, false)) {
            appendAttribute(output, "href", href);
            if ("_blank".equalsIgnoreCase(target)) {
                appendAttribute(output, "target", "_blank");
                appendAttribute(output, "rel", "noopener noreferrer");
            } else if (rel != null && rel.matches("(?i)[a-z\\s-]{1,80}")) {
                appendAttribute(output, "rel", rel);
            }
        }
        if ("img".equals(tag) && isSafeUrl(src, true)) {
            appendAttribute(output, "src", src);
            appendAttribute(output, "alt", truncate(alt, 300));
            if (width != null && SAFE_DIMENSION.matcher(width).matches() && Integer.parseInt(width) <= 2000) {
                appendAttribute(output, "width", width);
            }
            if (height != null && SAFE_DIMENSION.matcher(height).matches() && Integer.parseInt(height) <= 2000) {
                appendAttribute(output, "height", height);
            }
        }
        if (classes != null) {
            String safeClasses = safeQuillClasses(classes);
            if (!safeClasses.isEmpty()) {
                appendAttribute(output, "class", safeClasses);
            }
        }
        String safeStyle = safeStyle(style);
        if (!safeStyle.isEmpty()) {
            appendAttribute(output, "style", safeStyle);
        }
        if (!"a".equals(tag) && !"img".equals(tag)) {
            appendAttribute(output, "title", truncate(title, 300));
        }
    }

    private static String safeQuillClasses(String value) {
        StringBuilder result = new StringBuilder();
        for (String item : value.split("\\s+")) {
            if (item.matches("ql-(?:align-(?:center|right|justify)|indent-[1-8]|size-(?:small|large|huge)|font-(?:serif|monospace))")) {
                if (result.length() > 0) result.append(' ');
                result.append(item);
            }
        }
        return result.toString();
    }

    private static String safeStyle(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder result = new StringBuilder();
        for (String declaration : value.split(";")) {
            int separator = declaration.indexOf(':');
            if (separator <= 0) continue;
            String property = declaration.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String candidate = declaration.substring(separator + 1).trim();
            boolean accepted = switch (property) {
                case "color", "background-color" -> isSafeColor(candidate);
                case "font-size" -> Set.of("10px", "14px", "18px", "32px", "small", "large", "huge").contains(candidate.toLowerCase(Locale.ROOT));
                case "font-family" -> Set.of("serif", "sans-serif", "monospace").contains(candidate.toLowerCase(Locale.ROOT));
                case "text-align" -> Set.of("left", "right", "center", "justify").contains(candidate.toLowerCase(Locale.ROOT));
                default -> false;
            };
            if (accepted) {
                if (result.length() > 0) result.append(';');
                result.append(property).append(':').append(candidate);
            }
        }
        return result.toString();
    }

    private static boolean isSafeColor(String value) {
        String normalized = value.trim();
        return SAFE_HEX_COLOR.matcher(normalized).matches()
            || SAFE_COLOR_FUNCTION.matcher(normalized).matches()
            || NAMED_COLORS.contains(normalized.toLowerCase(Locale.ROOT));
    }

    private static boolean isSafeUrl(String value, boolean image) {
        if (value == null || value.isBlank() || value.length() > 4096 || value.matches(".*[\\u0000-\\u0020\\u007f].*")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (image && lower.matches("data:image/(?:png|jpeg|gif|webp);base64,[a-z0-9+/=]+")) {
            return true;
        }
        if (lower.startsWith("https://") || lower.startsWith("http://")) return true;
        if (!image && (lower.startsWith("mailto:") || lower.startsWith("tel:"))) return true;
        if (value.startsWith("//") || value.startsWith("\\\\") || value.contains("\\")) return false;
        if (value.contains(":")) return false;
        return !value.startsWith("/") || !value.startsWith("//");
    }

    private static void appendAttribute(StringBuilder output, String name, String value) {
        if (value == null || value.isEmpty()) return;
        output.append(' ').append(name).append("=\"");
        appendEscaped(output, value);
        output.append('"');
    }

    private static String truncate(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(value.length(), maxLength));
    }

    private static void appendEscaped(StringBuilder output, String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '&' -> output.append("&amp;");
                case '<' -> output.append("&lt;");
                case '>' -> output.append("&gt;");
                case '"' -> output.append("&quot;");
                default -> output.append(character);
            }
        }
    }
}
