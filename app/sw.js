/*
 * sw.js — offline support and automatic updates for the web app.
 * !!! Change VERSION on every web release: browsers then fetch the new files by themselves.
 */
var VERSION = 'kp-web-202609290837';
var FILES = ['./', 'index.html', 'app.css', 'app.js', 'core.js', 'i18n.js', 'intro.json', 'manifest.webmanifest',
  'lib/sql-wasm.js', 'lib/sql-wasm.wasm', 'fonts/vazirmatn_regular.ttf', 'fonts/vazirmatn_bold.ttf',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png'];

self.addEventListener('install', function (e) {
  e.waitUntil(caches.open(VERSION).then(function (c) { return c.addAll(FILES); }).then(function () { return self.skipWaiting(); }));
});

self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== VERSION; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener('fetch', function (e) {
  var url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;       // registration server etc.
  if (url.pathname.indexOf('/data/') >= 0) return;                                 // dictionary: always from the network
  e.respondWith(
    caches.match(e.request, {ignoreSearch: true}).then(function (hit) {
      return hit || fetch(e.request).catch(function () {
        if (e.request.mode === 'navigate') return caches.match('index.html');
      });
    })
  );
});
