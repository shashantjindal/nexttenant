/*
 * Stepping through a card's photos without leaving the results.
 *
 * Each card opens on a collage — one photo cannot show a room, a kitchen and
 * a balcony — and the arrows then walk through the photos one at a time, full
 * size, for somebody who is already interested.
 *
 * PROGRESSIVE ENHANCEMENT, and it is doing real work here. Every frame is in
 * the HTML already; CSS shows the one carrying --on and hides the rest. This
 * file only moves that class and unhides the controls. Without it the card is
 * a collage with no arrows, which is a perfectly good card rather than a
 * broken one.
 *
 * DELEGATED from the document rather than bound per card. A results page
 * carries twenty cards and "Load more" appends twenty more; binding on load
 * would leave every later card dead, and re-binding after each append is a
 * thing somebody forgets exactly once.
 */
(function () {
    'use strict';

    function framesIn(card) {
        return Array.prototype.slice.call(
                card.querySelectorAll('.listing-card__frame'));
    }

    function currentIndex(frames) {
        for (var i = 0; i < frames.length; i++) {
            if (frames[i].classList.contains('listing-card__frame--on')) {
                return i;
            }
        }
        return 0;
    }

    function show(card, index) {
        var frames = framesIn(card);
        if (frames.length === 0) {
            return;
        }
        // Wraps both ways: the arrow at the end goes back to the collage
        // rather than becoming a dead control, and there is no state where a
        // visible arrow does nothing.
        var next = (index + frames.length) % frames.length;

        frames.forEach(function (frame, i) {
            frame.classList.toggle('listing-card__frame--on', i === next);
        });

        var dots = card.querySelector('[data-card-dots]');
        if (dots) {
            Array.prototype.forEach.call(dots.children, function (dot, i) {
                dot.classList.toggle('listing-card__dot--on', i === next);
            });
        }
    }

    function prepare(card) {
        if (card.hasAttribute('data-card-ready')) {
            return;
        }
        var frames = framesIn(card);
        if (frames.length < 2) {
            return;   // nothing to step through
        }
        card.setAttribute('data-card-ready', '');

        var prev = card.querySelector('[data-card-prev]');
        var next = card.querySelector('[data-card-next]');
        if (prev) { prev.hidden = false; }
        if (next) { next.hidden = false; }

        var dots = card.querySelector('[data-card-dots]');
        if (dots) {
            frames.forEach(function (frame, i) {
                var dot = document.createElement('span');
                dot.className = 'listing-card__dot'
                        + (i === 0 ? ' listing-card__dot--on' : '');
                dots.appendChild(dot);
            });
            dots.hidden = false;
        }
    }

    /*
     * The arrows sit inside the card's stretched link, so a click on one would
     * otherwise follow the link to the listing. preventDefault is not enough
     * on its own — stopPropagation keeps the click from reaching the anchor
     * that covers the whole card.
     */
    document.addEventListener('click', function (event) {
        var button = event.target.closest('[data-card-prev], [data-card-next]');
        if (!button) {
            return;
        }
        event.preventDefault();
        event.stopPropagation();

        var card = button.closest('.listing-card__frames');
        if (!card) {
            return;
        }
        var step = button.hasAttribute('data-card-next') ? 1 : -1;
        show(card, currentIndex(framesIn(card)) + step);
    });

    /*
     * Prepared on hover and on focus rather than all at once on load.
     *
     * Twenty cards would otherwise mean twenty sets of dots built before
     * anybody has looked at one of them, and most visitors never touch the
     * arrows at all. Focusin covers the keyboard, where hover never happens.
     */
    ['pointerenter', 'focusin'].forEach(function (type) {
        document.addEventListener(type, function (event) {
            var card = event.target.closest
                    && event.target.closest('.listing-card__frames');
            if (card) {
                prepare(card);
            }
        }, true);
    });
}());
