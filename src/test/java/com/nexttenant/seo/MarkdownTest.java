package com.nexttenant.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Markdown subset used for area intros and guide bodies.
 *
 * <p>Most of these are security tests wearing formatting clothes. The output of
 * {@link Markdown} is written into pages with {@code th:utext} — unescaped —
 * so this class is the boundary between what an admin types and what a visitor's
 * browser executes. A failure here is not a formatting bug.
 */
class MarkdownTest {

    @Test
    @DisplayName("a script tag is text, not a script")
    void scriptTagIsEscaped() {
        String html = Markdown.toHtml("Hello <script>alert('xss')</script> there");

        assertThat(html).doesNotContain("<script");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("an event handler on an invented tag is text too")
    void inlineHtmlIsEscaped() {
        // A full CommonMark implementation passes inline HTML straight through
        // by design, which is exactly why this is not one.
        String html = Markdown.toHtml("<img src=x onerror=alert(1)> and some words");

        assertThat(html).doesNotContain("<img");
        assertThat(html).contains("&lt;img");
    }

    @Test
    @DisplayName("headings, bold, italic and bullets render")
    void rendersTheSubset() {
        String html = Markdown.toHtml("""
                ## Getting there

                The **fast** road is *quiet* after nine.

                - Lift
                - Parking
                """);

        assertThat(html).contains("<h2>Getting there</h2>");
        assertThat(html).contains("<strong>fast</strong>");
        assertThat(html).contains("<em>quiet</em>");
        assertThat(html).contains("<ul><li>Lift</li><li>Parking</li></ul>");
    }

    @Test
    @DisplayName("a site-relative link becomes a link")
    void internalLinksWork() {
        String html = Markdown.toHtml("See [rooms in Powai](/flats-in-powai) for more.");

        assertThat(html).contains("<a href=\"/flats-in-powai\">rooms in Powai</a>");
    }

    @Test
    @DisplayName("an external link stays plain text")
    void externalLinksAreNotLinks() {
        // Deliberate, and as much a product rule as a safety one: a compromised
        // admin account should not be able to turn the guides into a link farm
        // hosted on our domain.
        String html = Markdown.toHtml("Go to [this site](https://example.com) now.");

        assertThat(html).doesNotContain("<a href");
        assertThat(html).contains("example.com");
    }

    @Test
    @DisplayName("a protocol-relative link does not pass as a path")
    void protocolRelativeLinksAreRefused() {
        // "//evil.com" is a path to a naive pattern and an absolute URL to a
        // browser — the one case where a leading slash does not mean this site.
        String html = Markdown.toHtml("Try [here](//evil.com) instead.");

        assertThat(html).doesNotContain("<a href=\"//evil.com\"");
    }

    @Test
    @DisplayName("a dollar sign survives, rather than being read as a backreference")
    void dollarSignsAreLiteral() {
        // Matcher.replaceAll expands $1 in a replacement. This is the case that
        // would mangle or throw if the replacement were not quoted.
        String html = Markdown.toHtml("The **$1,000** deposit and a \\ backslash.");

        assertThat(html).contains("<strong>$1,000</strong>");
        assertThat(html).contains("\\");
    }

    @Test
    @DisplayName("a line that merely contains a dash is still a paragraph")
    void dashesMidSentenceAreNotBullets() {
        String html = Markdown.toHtml("Rent is high - and rising - near the lake.");

        assertThat(html).startsWith("<p>");
        assertThat(html).doesNotContain("<ul>");
    }

    @Test
    @DisplayName("wrapped lines inside a paragraph do not become line breaks")
    void softWrapsAreSpaces() {
        String html = Markdown.toHtml("One sentence\nwrapped by the author.");

        assertThat(html).isEqualTo("<p>One sentence wrapped by the author.</p>");
    }

    @Test
    @DisplayName("nothing in, nothing out")
    void emptyInputIsNull() {
        assertThat(Markdown.toHtml(null)).isNull();
        assertThat(Markdown.toHtml("   ")).isNull();
    }

    @Test
    @DisplayName("the first paragraph skips headings and bullets")
    void firstParagraphFindsProse() {
        String summary = Markdown.firstParagraph("""
                ## Powai

                - a bullet

                The lake side is quieter than the **Hiranandani** end.
                """);

        assertThat(summary).isEqualTo("The lake side is quieter than the Hiranandani end.");
    }
}
