/*
 * Photo gallery on the listing page.
 *
 * Progressive enhancement, same rule as area-filter.js: the server has already
 * rendered every frame, and this only changes WHICH one is visible. If the
 * script fails to load, the visitor sees the cover photo and the thumbnail
 * strip — degraded, not broken. That is the whole reason the arrow buttons
 * ship with the `hidden` attribute and are revealed here: a control that a
 * broken script leaves inert is worse than no control at all.
 */
(function () {
    'use strict';

    var gallery = document.getElementById('gallery');
    if (!gallery) {
        return;
    }

    var frames = Array.prototype.slice.call(gallery.querySelectorAll('.gallery__frame'));
    var thumbs = Array.prototype.slice.call(gallery.querySelectorAll('[data-gallery-go]'));
    var counter = gallery.querySelector('[data-gallery-count]');
    var prev = gallery.querySelector('[data-gallery-prev]');
    var next = gallery.querySelector('[data-gallery-next]');

    if (frames.length < 2) {
        // One photo needs no navigation. Leave the counter, which still reads
        // "1 / 1" and is honest.
        return;
    }

    prev.hidden = false;
    next.hidden = false;

    var current = 0;

    function show(index) {
        // Wrap in both directions: the last photo's "next" goes back to the
        // first. Clamping instead would leave the arrow visible but dead at
        // each end, which reads as a bug.
        current = (index + frames.length) % frames.length;

        frames.forEach(function (frame, i) {
            frame.classList.toggle('gallery__frame--on', i === current);
        });
        thumbs.forEach(function (thumb, i) {
            thumb.classList.toggle('gallery__thumb--on', i === current);
            thumb.setAttribute('aria-current', i === current ? 'true' : 'false');
        });

        if (counter) {
            counter.textContent = (current + 1) + ' / ' + frames.length;
        }
    }

    prev.addEventListener('click', function () { show(current - 1); });
    next.addEventListener('click', function () { show(current + 1); });

    thumbs.forEach(function (thumb) {
        thumb.addEventListener('click', function () {
            show(parseInt(thumb.getAttribute('data-gallery-go'), 10));
        });
    });

    /*
     * Arrow keys, but only while the gallery has focus inside it. Binding them
     * to the document would hijack the arrow keys for the whole page — a
     * visitor scrolling the cost breakdown with the keyboard would find the
     * photos changing under them instead.
     */
    gallery.addEventListener('keydown', function (event) {
        if (event.key === 'ArrowLeft') {
            event.preventDefault();
            show(current - 1);
        } else if (event.key === 'ArrowRight') {
            event.preventDefault();
            show(current + 1);
        }
    });

    /* Swipe, for the phone. A 40px threshold ignores the small horizontal
       drift in what was meant to be a vertical scroll. */
    var startX = null;
    var startY = null;
    var main = gallery.querySelector('.gallery__main');

    main.addEventListener('touchstart', function (event) {
        startX = event.changedTouches[0].clientX;
        startY = event.changedTouches[0].clientY;
    }, { passive: true });

    main.addEventListener('touchend', function (event) {
        if (startX === null) {
            return;
        }
        var dx = event.changedTouches[0].clientX - startX;
        var dy = event.changedTouches[0].clientY - startY;
        // Only act when the gesture was mostly horizontal, or a vertical scroll
        // that wanders sideways would flip the photo.
        if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy)) {
            show(dx < 0 ? current + 1 : current - 1);
        }
        startX = null;
        startY = null;
    }, { passive: true });
}());
