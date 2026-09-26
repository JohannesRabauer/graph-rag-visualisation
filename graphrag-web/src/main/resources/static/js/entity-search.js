(function () {
  'use strict';

  // Story 9.4: a name-prefix search box that pans/zooms/highlights an
  // Entity on the main-screen graph canvas — finding one visually on a
  // dense graph otherwise relies entirely on recognizing it or panning
  // around by hand. Kept in its own file per this codebase's
  // file-per-concern convention (see drift-tree.js/vector-space.js).

  var MAX_RESULTS = 8;

  var input = document.getElementById('entity-search-input');
  var resultsList = document.getElementById('entity-search-results');

  if (!input || !resultsList) {
    return;
  }

  var results = [];
  var activeIndex = -1;

  input.addEventListener('input', function () {
    var value = input.value.trim();
    if (!value || !window.GraphCanvas) {
      close();
      return;
    }
    results = window.GraphCanvas.searchEntities(value).slice(0, MAX_RESULTS);
    render();
  });

  input.addEventListener('keydown', function (event) {
    if (resultsList.hidden) {
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      moveActive(1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      moveActive(-1);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      select(activeIndex >= 0 ? activeIndex : 0);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      close();
    }
  });

  input.addEventListener('blur', function () {
    // Deferred so a click on a result (which itself blurs the input first)
    // still registers before the dropdown disappears.
    window.setTimeout(close, 150);
  });

  function moveActive(delta) {
    if (results.length === 0) {
      return;
    }
    activeIndex = (activeIndex + delta + results.length) % results.length;
    updateActiveClasses();
  }

  function render() {
    resultsList.textContent = '';
    activeIndex = -1;

    if (results.length === 0) {
      resultsList.hidden = true;
      input.setAttribute('aria-expanded', 'false');
      return;
    }

    results.forEach(function (entity, index) {
      var item = document.createElement('li');
      item.className = 'entity-search-result';
      item.setAttribute('role', 'option');
      item.dataset.index = String(index);

      var name = document.createElement('span');
      name.className = 'entity-search-result-name';
      name.textContent = entity.name;
      item.appendChild(name);

      if (entity.type) {
        var type = document.createElement('span');
        type.className = 'entity-search-result-type';
        type.textContent = entity.type;
        item.appendChild(type);
      }

      // mousedown (not click) fires before the input's blur handler closes
      // the dropdown, so the selection is still visible to select().
      item.addEventListener('mousedown', function (event) {
        event.preventDefault();
        select(index);
      });

      resultsList.appendChild(item);
    });

    resultsList.hidden = false;
    input.setAttribute('aria-expanded', 'true');
  }

  function updateActiveClasses() {
    var items = resultsList.querySelectorAll('.entity-search-result');
    for (var i = 0; i < items.length; i += 1) {
      items[i].classList.toggle('is-active', i === activeIndex);
      items[i].setAttribute('aria-selected', i === activeIndex ? 'true' : 'false');
    }
  }

  function select(index) {
    var entity = results[index];
    if (!entity || !window.GraphCanvas) {
      return;
    }
    window.GraphCanvas.focusEntity(entity.identity);
    input.value = '';
    close();
  }

  function close() {
    results = [];
    activeIndex = -1;
    resultsList.hidden = true;
    resultsList.textContent = '';
    input.setAttribute('aria-expanded', 'false');
  }

  window.EntitySearch = {
    close: close
  };
})();
