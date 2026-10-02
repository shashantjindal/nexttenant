package com.nexttenant.guide;

import com.nexttenant.seo.JsonLd;
import com.nexttenant.seo.PageMeta;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The guides, publicly.
 *
 * <p>They exist for the searches that come before somebody is looking at
 * rooms — "police verification for tenants in Mumbai", "what is a leave and
 * licence agreement", "how much deposit is normal" — which the listing pages
 * can never answer and which bring the kind of reader who is about to need a
 * flat.
 */
@Controller
public class GuideController {

    private final GuideRepository guides;
    private final JsonLd jsonLd;

    public GuideController(GuideRepository guides, JsonLd jsonLd) {
        this.guides = guides;
        this.jsonLd = jsonLd;
    }

    @GetMapping("/guides")
    public String index(Model model) {
        List<Guide> published = guides.findPublished();
        model.addAttribute("guides", published);

        model.addAttribute("pageMeta", PageMeta.page("Guides to renting in Mumbai")
                .describedAs("Plain answers about deposits, agreements, police verification "
                        + "and moving into a shared flat in Mumbai.")
                .canonical("/guides")
                // An index of nothing is a page about an absence. It renders,
                // because a link to it should not break, but it is not worth
                // indexing until there is something on it.
                .noindexIf(published.isEmpty())
                .structuredData(jsonLd.itemList("Guides", "/guides",
                        published.stream().map(Guide::path).toList(),
                        List.of(new JsonLd.Crumb("Home", "/"),
                                new JsonLd.Crumb("Guides", "/guides"))))
                .build());

        return "guide/index";
    }

    @GetMapping("/guides/{slug}")
    public String detail(@PathVariable String slug, Model model) {
        Guide guide = guides.findPublished(slug)
                // 404 for a draft as well as for a missing one. A separate
                // "not published yet" response would confirm the slug exists,
                // which is exactly the information an unpublished page should
                // not be leaking.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        model.addAttribute("guide", guide);

        model.addAttribute("pageMeta", PageMeta.page(guide.title())
                .describedAs(guide.summary())
                .canonical(guide.path())
                .ogType("article")
                .structuredData(jsonLd.article(
                        guide.title(), guide.summary(), guide.path(),
                        guide.author(), guide.publishedAt(), guide.updatedAt(), null,
                        List.of(new JsonLd.Crumb("Home", "/"),
                                new JsonLd.Crumb("Guides", "/guides"),
                                new JsonLd.Crumb(guide.title(), guide.path()))))
                .build());

        return "guide/detail";
    }
}
