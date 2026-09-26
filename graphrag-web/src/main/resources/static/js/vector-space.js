(function () {
  'use strict';

  // Story 8.5: the Vector Space tab's own scatter — corpus chunks rendered
  // at their settled, ingestion-time 2D positions (Story 8.1's projection,
  // fetched once and cached per corpus so re-opening the tab or asking
  // another question never reshuffles it), plus the query dot and its
  // top-k highlighted neighbors during Replay of a VECTOR trace. Driven by
  // replay.js exactly like GraphCanvas/DriftTree — same transport controls,
  // a different surface to draw on.

  var svg = document.getElementById('vector-space-scatter');
  var emptyNote = document.getElementById('vector-space-empty');

  if (!svg) {
    return;
  }

  var SVG_NS = 'http://www.w3.org/2000/svg';
  var VIEW_W = 600;
  var VIEW_H = 400;
  var PADDING = 40;

  var loadedForCorpusId = null;
  var chunkPositions = {}; // id -> {x, y} in raw data space
  var scaledChunkPositions = {}; // id -> {x, y} in SVG space
  var bounds = null;
  var dotsGroup = null;
  var overlayGroup = null;
  var currentQueryProjection = null;

  function init(corpusId) {
    if (!corpusId || corpusId === loadedForCorpusId) {
      return;
    }
    loadedForCorpusId = corpusId;
    clear();
    clearStaticDots();

    fetch('/api/corpora/' + corpusId + '/vector-space')
      .then(function (response) {
        return response.json();
      })
      .then(function (body) {
        if (loadedForCorpusId !== corpusId) {
          return; // a newer corpus was loaded before this fetch resolved
        }
        renderCorpus((body && body.chunks) || []);
      })
      .catch(function () {
        if (loadedForCorpusId === corpusId) {
          loadedForCorpusId = null;
        }
      });
  }

  function renderCorpus(chunks) {
    chunkPositions = {};
    scaledChunkPositions = {};
    if (!chunks.length) {
      if (emptyNote) {
        emptyNote.hidden = false;
      }
      return;
    }
    if (emptyNote) {
      emptyNote.hidden = true;
    }

    var minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    chunks.forEach(function (c) {
      chunkPositions[c.id] = { x: c.x, y: c.y };
      minX = Math.min(minX, c.x);
      maxX = Math.max(maxX, c.x);
      minY = Math.min(minY, c.y);
      maxY = Math.max(maxY, c.y);
    });
    // Guard against a degenerate (single-point or zero-variance) layout —
    // fall back to a fixed unit span so the scale function never divides by zero.
    if (!(maxX > minX)) { maxX = minX + 1; minX -= 1; }
    if (!(maxY > minY)) { maxY = minY + 1; minY -= 1; }
    bounds = { minX: minX, maxX: maxX, minY: minY, maxY: maxY };

    dotsGroup = document.createElementNS(SVG_NS, 'g');
    dotsGroup.setAttribute('class', 'vector-space-dots');
    Object.keys(chunkPositions).forEach(function (id) {
      var scaled = scalePoint(chunkPositions[id]);
      scaledChunkPositions[id] = scaled;
      var circle = document.createElementNS(SVG_NS, 'circle');
      circle.setAttribute('cx', String(scaled.x));
      circle.setAttribute('cy', String(scaled.y));
      circle.setAttribute('r', '4');
      circle.setAttribute('class', 'vector-space-chunk-dot');
      circle.dataset.chunkId = id;
      dotsGroup.appendChild(circle);
    });

    svg.textContent = '';
    svg.appendChild(dotsGroup);
    overlayGroup = document.createElementNS(SVG_NS, 'g');
    overlayGroup.setAttribute('class', 'vector-space-overlay');
    svg.appendChild(overlayGroup);
  }

  function scalePoint(point) {
    if (!bounds) {
      return { x: VIEW_W / 2, y: VIEW_H / 2 };
    }
    var xRatio = (point.x - bounds.minX) / (bounds.maxX - bounds.minX);
    var yRatio = (point.y - bounds.minY) / (bounds.maxY - bounds.minY);
    return {
      x: PADDING + xRatio * (VIEW_W - 2 * PADDING),
      // SVG y grows downward; flip so higher values plot higher, matching
      // how a reader expects a scatter to read.
      y: VIEW_H - (PADDING + yRatio * (VIEW_H - 2 * PADDING))
    };
  }

  // Called by replay.js each time the vector trace's step index changes —
  // `queryProjection` (a [x, y] pair) comes from the Replay CTA's own
  // dataset (upload.js stashes it there from the query response), since it
  // isn't part of the trace steps themselves.
  function highlightStep(steps, index, queryProjection) {
    if (!overlayGroup) {
      return;
    }
    currentQueryProjection = queryProjection || currentQueryProjection;
    overlayGroup.textContent = '';
    clearDotHighlights();

    if (index < 0 || !steps.length) {
      return;
    }

    // Step 0 is always VECTOR_QUERY_EMBEDDED — the query dot is visible
    // from that step onward.
    if (currentQueryProjection && bounds) {
      var queryPoint = scalePoint({ x: currentQueryProjection[0], y: currentQueryProjection[1] });
      var queryDot = document.createElementNS(SVG_NS, 'circle');
      queryDot.setAttribute('cx', String(queryPoint.x));
      queryDot.setAttribute('cy', String(queryPoint.y));
      queryDot.setAttribute('r', '6');
      queryDot.setAttribute('class', 'vector-space-query-dot');
      overlayGroup.appendChild(queryDot);

      // Every VECTOR_CHUNK step up to and including the current index is a
      // "retrieved so far" hit — draw its connecting line + score label and
      // highlight its dot.
      for (var i = 0; i <= index; i++) {
        var step = steps[i];
        if (!step || step.kind !== 'VECTOR_CHUNK') {
          continue;
        }
        var chunkPoint = scaledChunkPositions[step.identifier];
        if (!chunkPoint) {
          continue;
        }
        var line = document.createElementNS(SVG_NS, 'line');
        line.setAttribute('x1', String(queryPoint.x));
        line.setAttribute('y1', String(queryPoint.y));
        line.setAttribute('x2', String(chunkPoint.x));
        line.setAttribute('y2', String(chunkPoint.y));
        line.setAttribute('class', 'vector-space-hit-line');
        overlayGroup.appendChild(line);

        var label = document.createElementNS(SVG_NS, 'text');
        label.setAttribute('x', String(chunkPoint.x + 6));
        label.setAttribute('y', String(chunkPoint.y - 6));
        label.setAttribute('class', 'vector-space-hit-label');
        label.textContent = (step.label || '').replace('score=', '');
        overlayGroup.appendChild(label);

        highlightDot(step.identifier);
      }
    }
  }

  function highlightDot(chunkId) {
    if (!dotsGroup) {
      return;
    }
    var dot = dotsGroup.querySelector('[data-chunk-id="' + cssEscape(chunkId) + '"]');
    if (dot) {
      dot.classList.add('is-hit');
    }
  }

  function clearDotHighlights() {
    if (!dotsGroup) {
      return;
    }
    var hits = dotsGroup.querySelectorAll('.is-hit');
    for (var i = 0; i < hits.length; i++) {
      hits[i].classList.remove('is-hit');
    }
  }

  function clear() {
    currentQueryProjection = null;
    if (overlayGroup) {
      overlayGroup.textContent = '';
    }
    clearDotHighlights();
  }

  function clearStaticDots() {
    svg.textContent = '';
    dotsGroup = null;
    overlayGroup = null;
    bounds = null;
  }

  function cssEscape(value) {
    return String(value).replace(/["\\]/g, '\\$&');
  }

  window.VectorSpace = {
    init: init,
    highlightStep: highlightStep,
    clear: clear
  };
})();
