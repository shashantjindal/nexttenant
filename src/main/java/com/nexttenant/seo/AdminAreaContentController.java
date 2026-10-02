package com.nexttenant.seo;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Writing the prose that sits on each area page.
 *
 * <p>This exists because the alternative is generated text, and generated text
 * on a few hundred near-identical pages is the thing that gets a domain
 * demoted rather than the pages. "Powai has 34 rooms with an average rent of
 * ₹28,400" is a sentence assembled from a table; a reader can tell, and so can
 * Google. What the page needs is somebody who knows that Hiranandani is quiet
 * and expensive, that the lake side floods in July, and that the commute to
 * BKC is better than the map suggests.
 *
 * <p>So: a text box, a person, and no generation. The column is nullable and
 * the page renders without it, because a slow trickle of real writing beats a
 * complete set of filler.
 */
@Controller
public class AdminAreaContentController {

    /**
     * Below this, an intro is not doing the job it exists for.
     *
     * <p>Advisory rather than enforced here — the database constraint sets the
     * hard floor at 200 characters. This is the number the form shows while
     * somebody is typing, because two sentences about Powai is thin content
     * with extra steps.
     */
    private static final int SUGGESTED_MINIMUM = 600;

    private final LandingPageRepository landings;

    public AdminAreaContentController(LandingPageRepository landings) {
        this.landings = landings;
    }

    @GetMapping("/admin/areas")
    public String list(Model model) {
        model.addAttribute("areas", landings.allLocalities());
        model.addAttribute("pageMeta", PageMeta.noindex("Area pages — Admin"));
        return "admin/areas";
    }

    @GetMapping("/admin/areas/{slug}")
    public String edit(@PathVariable String slug, Model model) {
        AreaLanding area = landings.findAreaBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("area", area);
        model.addAttribute("preview", Markdown.toHtml(area.intro()));
        model.addAttribute("suggestedMinimum", SUGGESTED_MINIMUM);
        model.addAttribute("pageMeta", PageMeta.noindex(area.name() + " — Admin"));
        return "admin/area-content";
    }

    @PostMapping("/admin/areas/{slug}")
    public String save(@PathVariable String slug,
                       @RequestParam(required = false) String intro,
                       @RequestParam(required = false) String metaDescription,
                       Model model) {

        AreaLanding area = landings.findAreaBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String trimmed = intro == null ? "" : intro.strip();

        // The database rejects anything between 1 and 199 characters. Catching
        // it here means a sentence and a half comes back as an explanation
        // rather than as a constraint-violation stack trace.
        if (!trimmed.isEmpty() && trimmed.length() < 200) {
            model.addAttribute("area", area);
            model.addAttribute("suggestedMinimum", SUGGESTED_MINIMUM);
            model.addAttribute("error", "That is " + trimmed.length() + " characters. "
                    + "Either write at least a couple of hundred, or leave it empty — "
                    + "a page with two sentences on it is worse than one with none.");
            model.addAttribute("pageMeta", PageMeta.noindex(area.name() + " — Admin"));
            return "admin/area-content";
        }

        landings.saveIntro(area.id(), trimmed, metaDescription);
        return "redirect:/admin/areas";
    }
}
