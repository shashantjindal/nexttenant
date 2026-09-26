/*
 * Toast dismissal.
 *
 * The toasts are rendered by the server and are already visible and readable
 * with this file absent — that is the point of doing it in the layout rather
 * than building them in JavaScript. All this adds is the close button and the
 * auto-hide on confirmations.
 */
(function () {
    'use strict';

    /** How long a confirmation stays up. Errors never auto-hide; see base.html. */
    var AUTO_DISMISS_MS = 5000;

    function dismiss(toast) {
        toast.classList.add('toast--leaving');
        // Remove after the transition rather than immediately, so it fades
        // instead of blinking out. 200ms matches the CSS.
        window.setTimeout(function () {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 200);
    }

    var toasts = document.querySelectorAll('#toasts .toast');

    Array.prototype.forEach.call(toasts, function (toast) {
        var close = toast.querySelector('[data-toast-close]');
        if (close) {
            close.addEventListener('click', function () { dismiss(toast); });
        }

        if (toast.hasAttribute('data-toast-auto')) {
            var timer = window.setTimeout(function () { dismiss(toast); }, AUTO_DISMISS_MS);

            // Someone reading the message should not have it taken away
            // mid-sentence. Hovering or tabbing into it cancels the timer for
            // good, rather than restarting it on leave: if they looked at it
            // long enough to hover, they have read it and can close it.
            toast.addEventListener('mouseenter', function () { window.clearTimeout(timer); });
            toast.addEventListener('focusin', function () { window.clearTimeout(timer); });
        }
    });

    // Escape closes whatever is up. Cheap, and the thing everybody tries.
    if (toasts.length > 0) {
        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                Array.prototype.forEach.call(
                        document.querySelectorAll('#toasts .toast'), dismiss);
            }
        });
    }
}());
