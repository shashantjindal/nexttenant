/*
 * The filter panel as a drawer on a phone.
 *
 * The panel is first in the source — correct for a screen reader and for
 * desktop, where it sits beside the results — but on a phone it meant a
 * screen and a half of checkboxes before the first room. Somebody who came to
 * look at flats had to scroll past ninety areas to see one.
 *
 * CSS does the hiding and the sliding; this only toggles a class and traps
 * the page behind it. With no JavaScript the drawer button is invisible (CSS
 * shows it only under 768px, and this file unhides nothing) and the panel
 * renders inline exactly as it always did — long, and completely usable.
 */
(function () {
    'use strict';

    var panel = document.getElementById('filters-panel');
    var open = document.querySelector('[data-filters-open]');
    if (!panel || !open) {
        return;
    }

    function setOpen(isOpen) {
        panel.classList.toggle('filters--open', isOpen);
        // Stops the results scrolling behind the drawer, which otherwise
        // happens the moment a finger lands outside the panel's own content.
        document.body.classList.toggle('filters-open', isOpen);
        open.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    }

    open.setAttribute('aria-controls', 'filters-panel');
    open.setAttribute('aria-expanded', 'false');

    open.addEventListener('click', function () {
        setOpen(true);
        // Focus moves into the drawer, or a keyboard user is left tabbing
        // through the results they cannot see.
        var first = panel.querySelector('input, button, select, a');
        if (first) {
            first.focus();
        }
    });

    document.addEventListener('click', function (event) {
        if (event.target.closest('[data-filters-close]')) {
            setOpen(false);
            open.focus();
        }
    });

    // Escape closes it, which is what every other overlay on the web does.
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && panel.classList.contains('filters--open')) {
            setOpen(false);
            open.focus();
        }
    });
}());
