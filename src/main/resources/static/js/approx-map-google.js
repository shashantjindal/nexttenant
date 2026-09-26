/*
 * The approximate-location map, on Google Maps.
 *
 * The OpenStreetMap version of this file is still here (approx-map.js) and
 * still used when no API key is configured; see MapsProperties. The two are
 * kept as separate files rather than one file with a branch, because the two
 * APIs have nothing in common beyond "draw a circle" and a single file holding
 * both would be two implementations sharing a name.
 *
 * PRIVACY, unchanged from the Leaflet version and worth repeating because it
 * is the thing this page exists to protect: this reads ONLY the jittered point
 * and the published radius from data attributes. ListingDetailDto has no field
 * for the exact location, so the real one is not in the page at all. Nothing
 * here could plot it even if it tried to.
 */
(function () {
    'use strict';

    /*
     * A muted style. Google's default map is designed to sell you a
     * restaurant: saturated POI pins everywhere, each one a thing the eye
     * lands on before it finds the circle we actually drew. Stripping the
     * points of interest is not decoration, it is the difference between the
     * circle being the subject of the image and being decoration itself.
     */
    var MUTED = [
        { featureType: 'poi', stylers: [{ visibility: 'off' }] },
        { featureType: 'transit.station.bus', stylers: [{ visibility: 'off' }] },
        { featureType: 'road', elementType: 'labels.icon', stylers: [{ visibility: 'off' }] }
    ];

    // Google calls this by name once its script has loaded — the callback in
    // the loader URL. It must therefore be on window, not scoped to this IIFE.
    window.hhInitApproxMap = function () {
        var el = document.getElementById('approx-map');
        if (!el || typeof google === 'undefined') {
            return;
        }

        var lat = parseFloat(el.getAttribute('data-lat'));
        var lng = parseFloat(el.getAttribute('data-lng'));
        var radius = parseInt(el.getAttribute('data-radius'), 10);

        if (!isFinite(lat) || !isFinite(lng) || lat === 0) {
            el.remove();
            return;
        }

        var centre = { lat: lat, lng: lng };

        var map = new google.maps.Map(el, {
            center: centre,
            zoom: 15,
            // The map sits partway down a long page. Leaving the wheel bound
            // to zoom traps anyone scrolling past it — they reach the map and
            // the page stops moving. Ctrl+wheel still zooms.
            gestureHandling: 'cooperative',
            mapTypeControl: false,
            streetViewControl: false,
            fullscreenControl: false,
            styles: MUTED
        });

        var circle = new google.maps.Circle({
            map: map,
            center: centre,
            radius: radius,
            strokeColor: '#7A3420',
            strokeOpacity: 0.9,
            strokeWeight: 1.5,
            fillColor: '#7A3420',
            fillOpacity: 0.12,
            clickable: false
        });

        // No marker. A pin invites the reading "the flat is exactly here",
        // which is precisely what the circle exists to avoid saying.
        map.fitBounds(circle.getBounds());
    };
}());
