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

    /*
     * Money fields take digits and nothing else.
     *
     * These are type="text" rather than type="number" on purpose — a number
     * input will not accept "32,000", and the rest of the site renders rents
     * with exactly those separators, so refusing to take one back is its own
     * kind of wrong. The cost of text is that the field accepted "0234ax",
     * which the server stripped to "0234" and saved as ₹234: a wrong number,
     * silently, with nothing on screen to say so.
     *
     * Stripping as you type makes the rule visible — the letter simply never
     * appears — instead of reporting it afterwards or, worse, not at all. The
     * server refuses the same input independently, because this runs in the
     * browser and anything running in the browser is a convenience.
     *
     * The caret is restored by hand. Assigning to value moves it to the end,
     * so editing the start of "32000" would throw you to the far end after
     * every keystroke.
     */
    document.addEventListener('input', function (event) {
        var el = event.target;
        if (!el || !el.hasAttribute || !el.hasAttribute('data-digits')) {
            return;
        }

        var before = el.value;
        var caret = el.selectionStart;
        var after = before.replace(/[^0-9,]/g, '');

        if (after !== before) {
            el.value = after;
            // Move the caret back by however many characters vanished before
            // it, so it stays beside the same digit it was beside.
            var removed = before.slice(0, caret).length
                    - before.slice(0, caret).replace(/[^0-9,]/g, '').length;
            try {
                el.setSelectionRange(caret - removed, caret - removed);
            } catch (ignored) {
                // Some browsers refuse setSelectionRange on certain input
                // types. Losing the caret position is worth less than the
                // strip, so this failing must not stop it.
            }
        }
    });

    /*
     * A six-digit code submits itself.
     *
     * Typing the last digit and then reaching for a button is a step that
     * exists only because the form needs one. The code has exactly one valid
     * length, so the moment six digits are present there is nothing further to
     * ask — and on a phone, where the code was just autofilled from the
     * keyboard bar, the button is often below the fold.
     *
     * Two things this deliberately does NOT do. It does not fire more than
     * once: five wrong attempts and the code is dead, so a handler that could
     * resubmit on a later input event would be a way to burn a person's
     * attempts for them. And it does not remove the button, which is what
     * anyone without JavaScript — and anyone correcting a typo before the
     * sixth digit lands — still uses.
     */
    /*
     * The example number gets out of the way when you tap the field.
     *
     * A placeholder reading "32000" in a rent box looks like a value already
     * entered — grey text in a field is how every form on the internet shows
     * something it has filled in for you. People tapped in, saw a number, and
     * were not sure whether they were adding to it or replacing it.
     *
     * Clearing it on focus and restoring it on blur keeps the example for
     * anybody who has not got there yet, and removes the ambiguity for the one
     * person actually looking at the field.
     */
    document.addEventListener('focusin', function (event) {
        var el = event.target;
        if (!el || !el.hasAttribute) {
            return;
        }

        if (el.hasAttribute('data-ghost') && el.placeholder) {
            el.setAttribute('data-ghost-text', el.placeholder);
            el.placeholder = '';
        }

        /*
         * A prefilled zero clears itself when you tap into it.
         *
         * Brokerage and buy-out arrive as "0" so the common answer needs no
         * typing — but a real 0 behaves differently from a placeholder: it
         * does not vanish when you start typing, so anyone entering 5000 gets
         * "05000" or has to select-all first. Clearing on focus makes the
         * field behave the way it LOOKS like it behaves.
         *
         * Restored on blur if nothing was typed, so the answer is still zero
         * for everybody who only passed through.
         */
        if (el.hasAttribute('data-ghost-zero') && el.value.trim() === '0') {
            el.value = '';
            el.setAttribute('data-was-zero', '');
        }
    });

    document.addEventListener('focusout', function (event) {
        var el = event.target;
        if (!el || !el.hasAttribute) {
            return;
        }
        if (el.hasAttribute('data-ghost-text')) {
            el.placeholder = el.getAttribute('data-ghost-text');
        }
        // Left empty after the zero was cleared: put it back, so merely
        // passing through the field does not turn a stated zero into a blank.
        if (el.hasAttribute('data-was-zero')) {
            if (el.value.trim() === '') {
                el.value = '0';
            }
            el.removeAttribute('data-was-zero');
        }
    });

    /*
     * A follow-up question that appears only when it applies.
     *
     * "Who already lives there" is meaningless once the room is men-only or
     * women-only � those answers say it. Rendered in the markup and hidden
     * with the hidden attribute, so without JavaScript it simply shows all
     * the time: one extra optional question, which is far better than a
     * question nobody can reach.
     */
    var anyoneOnly = document.querySelector("[data-when-anyone]");
    if (anyoneOnly) {
        document.addEventListener("change", function (event) {
            var el = event.target;
            if (!el || el.name !== "flatmateGender") {
                return;
            }
            var applies = el.value === "ANY";
            anyoneOnly.hidden = !applies;

            // Clearing on the way out matters: a stale "all men" would
            // otherwise be posted alongside "women only". The server clears it
            // too, because a hidden field is not a rule.
            if (!applies) {
                Array.prototype.forEach.call(
                        anyoneOnly.querySelectorAll("input"), function (box) {
                            box.checked = false;
                        });
            }
        });
    }

    var otp = document.querySelector('input[autocomplete="one-time-code"]');
    if (otp && otp.form) {
        var sent = false;
        otp.addEventListener('input', function () {
            if (sent) {
                return;
            }
            if (/^[0-9]{6}$/.test(otp.value.trim())) {
                sent = true;
                // requestSubmit, not submit: it runs the form's own validation
                // and fires the submit event, where plain submit() skips both.
                if (otp.form.requestSubmit) {
                    otp.form.requestSubmit();
                } else {
                    otp.form.submit();
                }
            }
        });
    }
}());
