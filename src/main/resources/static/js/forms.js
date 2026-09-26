/*
 * Small form behaviours that apply everywhere.
 */
(function () {
    'use strict';

    /*
     * Stop a focused <input type="number"> eating the scroll wheel.
     *
     * A number input responds to the wheel ONLY while it has focus. So the
     * annoyance has a precise shape: you click into "Floor", type 7, then
     * scroll on to the next question — and because the cursor is still over
     * the field you just used, the page stays put and the floor silently
     * becomes 12. You have changed an answer you already gave, and nothing
     * told you.
     *
     * Blurring is the right response rather than preventDefault(). Cancelling
     * the wheel event stops the value changing AND stops the page scrolling,
     * which is worse: the field becomes a dead patch that swallows scrolling.
     * Blurring gives the wheel back to the document, so the page moves exactly
     * as it would over any other part of the form.
     *
     * Listening on the document with capture, rather than binding each input,
     * so fields rendered later are covered too. passive: true because this
     * never calls preventDefault, and saying so lets the browser scroll
     * without waiting for this handler.
     */
    document.addEventListener('wheel', function (event) {
        var el = event.target;
        if (el && el.type === 'number' && el === document.activeElement) {
            el.blur();
        }
    }, { passive: true, capture: true });

    /*
     * The same thing for the keyboard's version of the problem: arrow keys on
     * a focused number field scroll nothing and increment instead, which is
     * fine and expected — that one is left alone. Only the wheel is surprising,
     * because the wheel is how you move the page.
     */
}());
