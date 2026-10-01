// GraphRAG Lens website — small progressive enhancements.
// Everything on the page works without this file; it only adds the
// mode tabs' switching, the scroll-spy in the nav, reveal-on-scroll and
// the copy buttons on the command blocks.
(function () {
  'use strict';

  // ---- Search-mode tabs -------------------------------------------------
  var tabs = Array.prototype.slice.call(document.querySelectorAll('.mode-tab'));
  var panels = Array.prototype.slice.call(document.querySelectorAll('.mode-panel'));

  function selectMode(mode, focus) {
    tabs.forEach(function (tab) {
      var on = tab.getAttribute('data-mode') === mode;
      tab.setAttribute('aria-selected', on ? 'true' : 'false');
      tab.setAttribute('tabindex', on ? '0' : '-1');
      if (on && focus) tab.focus();
    });
    panels.forEach(function (panel) {
      var on = panel.getAttribute('data-mode') === mode;
      panel.classList.toggle('is-active', on);
      panel.hidden = !on;
    });
  }

  tabs.forEach(function (tab, index) {
    tab.addEventListener('click', function () {
      selectMode(tab.getAttribute('data-mode'), false);
    });
    tab.addEventListener('keydown', function (event) {
      var next;
      if (event.key === 'ArrowRight') next = tabs[(index + 1) % tabs.length];
      if (event.key === 'ArrowLeft') next = tabs[(index - 1 + tabs.length) % tabs.length];
      if (next) {
        event.preventDefault();
        selectMode(next.getAttribute('data-mode'), true);
      }
    });
  });

  // Deep links such as #mode-global select that tab.
  function selectFromHash() {
    var match = /^#mode-(local|global|drift)$/.exec(location.hash);
    if (match) selectMode(match[1], false);
  }
  selectFromHash();
  window.addEventListener('hashchange', selectFromHash);
  if (tabs.length && !tabs.some(function (t) { return t.getAttribute('aria-selected') === 'true'; })) {
    selectMode('local', false);
  }

  // ---- Copy buttons -----------------------------------------------------
  Array.prototype.forEach.call(document.querySelectorAll('.codeblock[data-copy]'), function (block) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-btn';
    button.textContent = 'Copy';
    button.setAttribute('aria-label', 'Copy command to clipboard');
    button.addEventListener('click', function () {
      var text = block.getAttribute('data-copy');
      var done = function () {
        button.textContent = 'Copied';
        setTimeout(function () { button.textContent = 'Copy'; }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, done);
      } else {
        var area = document.createElement('textarea');
        area.value = text;
        document.body.appendChild(area);
        area.select();
        try { document.execCommand('copy'); } catch (e) { /* ignore */ }
        document.body.removeChild(area);
        done();
      }
    });
    block.appendChild(button);
  });

  // ---- Reveal on scroll -------------------------------------------------
  var reveals = Array.prototype.slice.call(document.querySelectorAll('.reveal'));
  if ('IntersectionObserver' in window && reveals.length) {
    var revealObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          revealObserver.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });
    reveals.forEach(function (el) { revealObserver.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add('is-visible'); });
  }

  // ---- Scroll-spy for the top navigation -------------------------------
  var navLinks = Array.prototype.slice.call(document.querySelectorAll('.nav-links a[href^="#"]'));
  var sections = navLinks
    .map(function (link) { return document.querySelector(link.getAttribute('href')); })
    .filter(Boolean);

  function setActive(id) {
    navLinks.forEach(function (link) {
      var on = link.getAttribute('href') === '#' + id;
      link.classList.toggle('is-active', on);
      if (on) link.setAttribute('aria-current', 'true'); else link.removeAttribute('aria-current');
    });
  }

  if ('IntersectionObserver' in window && sections.length) {
    var spyObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) setActive(entry.target.id);
      });
    }, { rootMargin: '-40% 0px -55% 0px', threshold: 0 });
    sections.forEach(function (section) { spyObserver.observe(section); });
  }

  // ---- Footer year ------------------------------------------------------
  var year = document.getElementById('year');
  if (year) year.textContent = String(new Date().getFullYear());
})();
