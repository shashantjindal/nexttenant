/*
 * Applies the stylesheets that were loaded without blocking the first paint.
 *
 * WHY THIS IS A FILE AND NOT AN onload ATTRIBUTE.
 *
 * The usual recipe for non-blocking CSS is
 *
 *     <link rel="stylesheet" href="..." media="print" onload="this.media='all'">
 *
 * and on this site it silently does not work. SecurityConfig sets a Content
 * Security Policy whose script-src is 'self' with no 'unsafe-inline', and an
 * inline event handler IS inline script as far as CSP is concerned. The browser
 * blocks it, the media attribute stays "print", and the stylesheets never
 * apply — so the page renders with only the inlined critical CSS, looking
 * subtly wrong, while every performance metric improves. It was caught by a
 * Lighthouse run reporting faster paints and newly broken images at the same
 * time, which is the only reason it was caught at all.
 *
 * Loosening the CSP to permit inline handlers would be the wrong trade: inline
 * script is precisely what an injection attack needs. A same-origin file costs
 * one cached request and keeps the policy strict.
 */
(function () {
    'use strict';

    function apply() {
        var links = document.querySelectorAll('link[data-async-css]');
        for (var i = 0; i < links.length; i++) {
            links[i].media = 'all';
        }
    }

    // The script is deferred, so the document has been parsed and every link is
    // present. readyState is checked anyway: if this file is ever moved or
    // loaded some other way, "already interactive" must not mean "never runs".
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', apply);
    } else {
        apply();
    }
})();
