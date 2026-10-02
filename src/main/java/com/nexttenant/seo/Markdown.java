package com.nexttenant.seo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small Markdown subset, for area intros and guide bodies.
 *
 * <h2>Why not a Markdown library</h2>
 *
 * <p>Because the output of this class is written into pages with
 * {@code th:utext} — unescaped — which makes it part of the security boundary
 * rather than a formatting convenience. A full CommonMark implementation
 * supports inline HTML by design: {@code <script>alert(1)</script>} in a guide
 * body is valid Markdown and a correct renderer passes it straight through.
 * Using one safely means pairing it with an HTML sanitiser and keeping both
 * configured correctly forever.
 *
 * <p>This instead escapes EVERYTHING first and then introduces a fixed, closed
 * set of tags. There is no path by which author text becomes markup: by the
 * time any rule runs, every {@code <} in the input is already {@code &lt;}. The
 * cost is that the subset is small. For a few paragraphs about Powai and a
 * how-to about police verification, it is enough.
 *
 * <h2>What it supports</h2>
 *
 * <ul>
 *   <li>{@code ## Heading} and {@code ### Heading} — not {@code #}, because the
 *       page already has one h1 and a second is a structure error</li>
 *   <li>Paragraphs, separated by a blank line</li>
 *   <li>{@code - } bullet lists</li>
 *   <li>{@code **bold**} and {@code *italic*}</li>
 *   <li>{@code [text](/path)} links, <b>site-relative only</b></li>
 * </ul>
 */
public final class Markdown {

    /**
     * Links must start with a single slash.
     *
     * <p>So an author cannot add an external link at all. That is a product
     * decision as much as a safety one: an editor-written page linking out to
     * an arbitrary domain is how a compromised admin account turns into a
     * link-spam farm hosted on our domain, and the guides have no legitimate
     * reason to leave the site. {@code //evil.com} is excluded by the second
     * character check — it is protocol-relative and leaves the site while
     * looking like a path.
     */
    private static final Pattern LINK =
            Pattern.compile("\\[([^\\]\\n]{1,120})]\\((/[^)\\s\"']{0,200})\\)");

    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\n]{1,200})\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*([^*\\n]{1,200})\\*(?!\\*)");

    private Markdown() {
    }

    /**
     * Renders to HTML that is safe to emit unescaped.
     *
     * @param markdown the author's text, or null
     * @return HTML, or null for null input so a caller can test one thing
     */
    public static String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }

        // EVERY other rule in this class runs after this line, and that order is
        // the entire security argument. Escaping last, or per-rule, is how
        // sanitisers get bypassed.
        String safe = escape(markdown);

        StringBuilder html = new StringBuilder(safe.length() + 64);
        List<String> bullets = new ArrayList<>();

        // Normalised so that a file written on Windows and one written in a
        // browser textarea split into the same blocks.
        for (String block : safe.replace("\r\n", "\n").replace('\r', '\n').split("\n\\s*\n")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }

            bullets.clear();
            for (String line : trimmed.split("\n")) {
                if (line.strip().startsWith("- ")) {
                    bullets.add(line.strip().substring(2).strip());
                }
            }

            // A block is a list only if EVERY line in it is a bullet. A
            // paragraph that happens to contain a dash mid-sentence stays a
            // paragraph.
            if (!bullets.isEmpty() && bullets.size() == trimmed.split("\n").length) {
                html.append("<ul>");
                for (String bullet : bullets) {
                    html.append("<li>").append(inline(bullet)).append("</li>");
                }
                html.append("</ul>");
            } else if (trimmed.startsWith("### ")) {
                html.append("<h3>").append(inline(trimmed.substring(4).strip())).append("</h3>");
            } else if (trimmed.startsWith("## ")) {
                html.append("<h2>").append(inline(trimmed.substring(3).strip())).append("</h2>");
            } else {
                // Single newlines inside a paragraph become spaces, not <br>.
                // Authors wrap their lines; they do not mean a line break.
                html.append("<p>").append(inline(trimmed.replace("\n", " "))).append("</p>");
            }
        }

        return html.toString();
    }

    /**
     * The first paragraph as plain text, for a summary or a description.
     *
     * <p>Reads the ORIGINAL markdown rather than stripping tags back out of the
     * rendered HTML, because un-rendering is exactly the kind of round trip
     * that reintroduces what the rendering removed.
     */
    public static String firstParagraph(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        for (String block : markdown.replace("\r\n", "\n").split("\n\\s*\n")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                continue;
            }
            return trimmed.replace("\n", " ")
                    .replaceAll("\\*\\*|\\*", "")
                    .replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1");
        }
        return null;
    }

    /** Bold, italic and links — applied only to already-escaped text. */
    private static String inline(String escaped) {
        String html = replace(BOLD, escaped, m -> "<strong>" + m.group(1) + "</strong>");
        html = replace(ITALIC, html, m -> "<em>" + m.group(1) + "</em>");
        return replace(LINK, html, m -> {
            String href = m.group(2);
            // Protocol-relative: "//evil.com" is a path as far as the pattern
            // above is concerned and an absolute URL to a browser. The one case
            // where a leading slash does not mean "this site", so it is left as
            // the literal text the author typed.
            if (href.startsWith("//")) {
                return m.group(0);
            }
            return "<a href=\"" + href + "\">" + m.group(1) + "</a>";
        });
    }

    /**
     * Find-and-replace where the replacement is taken literally.
     *
     * <p>An explicit loop rather than {@code Matcher.replaceAll(Function)}:
     * that overload expands {@code $1} and backslashes in whatever the function
     * returns, so a guide containing a price like "$1" or a Windows path would
     * come out mangled or throw. {@link Matcher#quoteReplacement} applied here
     * — once, around the finished fragment — removes the question.
     */
    private static String replace(Pattern pattern, String input,
                                  java.util.function.Function<Matcher, String> build) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder(input.length() + 32);
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(build.apply(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
