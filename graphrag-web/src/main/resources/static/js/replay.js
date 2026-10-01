(function () {
  'use strict';

  // Owns the Retrieval Trace scrubber (Story 5.2): fetches a trace when a
  // chat answer's Replay CTA is clicked, tracks the current step, wires the
  // transport controls (keyboard-operable via plain <button>s), the step
  // tick track (click and drag-to-nearest-step), the step counter/caption,
  // and the graph canvas eyebrow swap. Kept out of upload.js/graph-canvas.js
  // per Story 4.3's file-per-concern convention. Replay is strictly
  // post-hoc — it only ever opens after a trace already exists; nothing
  // here runs during a live/streaming query.

  var PLAY_INTERVAL_MS = 1400;
  var RESTING_EYEBROW_TEXT = 'Knowledge Graph — Resting';
  var REPLAYING_EYEBROW_TEXT = 'Knowledge Graph — Replaying Trace';

  var scrubber = document.getElementById('replay-scrubber');
  var closeButton = document.getElementById('replay-close');
  var stepBackButton = document.getElementById('replay-step-back');
  var playPauseButton = document.getElementById('replay-play-pause');
  var stepForwardButton = document.getElementById('replay-step-forward');
  var tickTrack = document.getElementById('replay-tick-track');
  var stepCounterEl = document.getElementById('replay-step-counter');
  var captionEl = document.getElementById('replay-caption');
  var graphEyebrow = document.getElementById('graph-eyebrow');

  // The bar's caption is one short line; the Retrieval Trace pane
  // (trace-pane.js) carries the full label and the reason for every step.
  var CAPTION_LABEL_CHARS = 120;

  if (!scrubber || !stepBackButton || !playPauseButton || !stepForwardButton) {
    var missing = [];
    if (!scrubber) {
      missing.push('#replay-scrubber');
    }
    if (!stepBackButton) {
      missing.push('#replay-step-back');
    }
    if (!playPauseButton) {
      missing.push('#replay-play-pause');
    }
    if (!stepForwardButton) {
      missing.push('#replay-step-forward');
    }
    console.error('[replay] missing required DOM element(s): ' + missing.join(', '));
    return;
  }

  var steps = [];
  var currentIndex = 0;
  var playTimer = null;
  var dragging = false;
  var requestSeq = 0;
  var loadError = false;
  var isVectorTrace = false;
  var queryProjection = null;
  // Story 15.4: the corpus the replayed answer belongs to, so TEXT_UNIT
  // captions can name their passage (document and ordinal).
  var traceCorpusId = null;
  // The search mode of the replayed answer (LOCAL, GLOBAL, DRIFT, VECTOR),
  // read off the Replay CTA; the trace pane names it and groups the steps
  // into that mode's phases.
  var traceMode = null;
  // Passages of the open trace that failed to load; not fetched again.
  var failedPassageIds = {};

  // The bar grows with its wrapped caption; publish its height so the drift
  // tree and help buttons stacked above it never overlap it.
  if (window.ResizeObserver && scrubber.parentElement) {
    var stage = scrubber.parentElement;
    new ResizeObserver(function () {
      if (!scrubber.hidden) {
        stage.style.setProperty('--replay-h', scrubber.offsetHeight + 'px');
      }
    }).observe(scrubber);
  }

  // Delegated so it keeps working for every Replay CTA `upload.js` appends
  // later, without either file needing to know about the other's timing.
  document.addEventListener('click', function (event) {
    var cta = event.target && event.target.closest ? event.target.closest('.replay-cta') : null;
    if (!cta) {
      return;
    }
    // Story 8.5: a VECTOR answer's Replay CTA also carries its query's own
    // 2D projection (stashed by upload.js from the query response) — the
    // trace steps themselves only carry chunk ids/scores, not coordinates.
    var projection = null;
    if (cta.dataset.queryProjection) {
      try {
        projection = JSON.parse(cta.dataset.queryProjection);
      } catch (e) {
        projection = null;
      }
    }
    // A chat answer's CTA reads the corpus off its message; the Compare
    // view's replay buttons carry it themselves.
    var answerMsg = cta.closest('.message.answer');
    openReplay(cta.dataset.traceId, projection,
        answerMsg ? answerMsg.dataset.corpusId : (cta.dataset.corpusId || null),
        cta.dataset.mode || (answerMsg ? answerMsg.dataset.mode : null) || null);
  });

  if (closeButton) {
    closeButton.addEventListener('click', closeReplay);
  }
  stepBackButton.addEventListener('click', stepBack);
  stepForwardButton.addEventListener('click', stepForward);
  playPauseButton.addEventListener('click', togglePlay);

  if (tickTrack) {
    tickTrack.addEventListener('pointerdown', function (event) {
      if (steps.length === 0) {
        return;
      }
      dragging = true;
      if (tickTrack.setPointerCapture) {
        tickTrack.setPointerCapture(event.pointerId);
      }
      stopPlayback();
      goToStep(nearestStepIndexForPointer(event));
    });
    // Bound on the track itself (not window): with setPointerCapture above,
    // the track keeps receiving pointermove even once the pointer leaves its
    // bounding box, so a fast drag past either edge still clamps to the
    // first/last step instead of freezing.
    tickTrack.addEventListener('pointermove', function (event) {
      if (!dragging) {
        return;
      }
      goToStep(nearestStepIndexForPointer(event));
    });
    tickTrack.addEventListener('pointerup', function () {
      dragging = false;
    });
    tickTrack.addEventListener('pointercancel', function () {
      dragging = false;
    });
  }

  function openReplay(traceId, projection, corpusId, mode) {
    if (!traceId) {
      return;
    }
    stopPlayback();
    hideFetchError();
    queryProjection = projection;
    traceMode = mode || null;

    // Guards against an out-of-order response: if a second Replay CTA is
    // clicked before this fetch resolves, only the most recently requested
    // trace's response is allowed to populate the scrubber.
    var thisRequest = ++requestSeq;

    fetch('/api/traces/' + traceId)
      .then(function (response) {
        return response.json().then(function (body) {
          return { ok: response.ok, body: body };
        });
      })
      .then(function (result) {
        if (thisRequest !== requestSeq) {
          return;
        }
        if (!result.ok) {
          showFetchError();
          return;
        }
        loadError = false;
        traceCorpusId = corpusId || null;
        failedPassageIds = {};
        steps = (result.body && result.body.steps) || [];
        currentIndex = 0;
        isVectorTrace = steps.length > 0 && steps[0].kind === 'VECTOR_QUERY_EMBEDDED';
        if (window.DriftTree) {
          window.DriftTree.build(steps);
        }
        open();
        if (window.TracePane) {
          window.TracePane.open(steps, {
            mode: traceMode,
            corpusId: traceCorpusId,
            surface: isVectorTrace ? 'vector-space' : 'knowledge-graph'
          });
        }
        buildTicks();
        renderStep();
      })
      .catch(function () {
        if (thisRequest !== requestSeq) {
          return;
        }
        showFetchError();
      });
  }

  function showFetchError() {
    loadError = true;
    steps = [];
    currentIndex = 0;
    if (window.DriftTree) {
      window.DriftTree.clear();
    }
    open();
    if (window.TracePane) {
      window.TracePane.open([], {
        mode: traceMode,
        loadError: true,
        surface: isVectorTrace ? 'vector-space' : 'knowledge-graph'
      });
    }
    buildTicks();
    updateCounterAndCaption();
    updateTicks();
    updateTransportState();
  }

  function hideFetchError() {
    loadError = false;
  }

  function open() {
    // Story 8-3/8.5: Knowledge Graph and Vector Space are mutually exclusive
    // tabs. A VECTOR trace's Replay opens on the Vector Space surface (its
    // scatter, not the graph canvas); every other trace kind still forces
    // the Knowledge Graph tab, as before.
    if (window.CanvasTabs) {
      window.CanvasTabs.switchTo(isVectorTrace ? 'vector-space' : 'knowledge-graph');
    }
    scrubber.hidden = false;
    if (!isVectorTrace && graphEyebrow) {
      graphEyebrow.hidden = false;
      graphEyebrow.textContent = REPLAYING_EYEBROW_TEXT;
    }
  }

  function closeReplay() {
    stopPlayback();
    scrubber.hidden = true;
    if (graphEyebrow) {
      graphEyebrow.textContent = RESTING_EYEBROW_TEXT;
    }
    if (window.DriftTree) {
      window.DriftTree.clear();
    }
    if (window.TracePane) {
      window.TracePane.clear();
    }
    if (window.GraphCanvas) {
      window.GraphCanvas.clearStepHighlights();
    }
    if (window.VectorSpace) {
      window.VectorSpace.clear();
    }
  }

  // The trace pane's step rows jump the replay to their step.
  function goTo(index) {
    stopPlayback();
    goToStep(Number(index) || 0);
  }

  // Exposed so upload.js can close an open Replay before it reinitializes
  // the Cytoscape canvas for a newly-loaded Corpus (Story 4.3's
  // GraphCanvas.init()) — otherwise a still-running autoplay interval would
  // keep calling highlightStep with a stale trace's node ids against a
  // rebuilt, unrelated graph.
  window.Replay = {
    close: closeReplay,
    goTo: goTo
  };

  function buildTicks() {
    if (!tickTrack) {
      return;
    }
    tickTrack.textContent = '';
    steps.forEach(function (step, index) {
      var tick = document.createElement('button');
      tick.type = 'button';
      tick.className = 'replay-tick';
      tick.dataset.index = String(index);
      tick.setAttribute('aria-label', 'Step ' + (index + 1) + ' of ' + steps.length);
      tick.addEventListener('click', function () {
        stopPlayback();
        goToStep(index);
      });
      tickTrack.appendChild(tick);
    });
  }

  function nearestStepIndexForPointer(event) {
    if (steps.length <= 1) {
      return 0;
    }
    var rect = tickTrack.getBoundingClientRect();
    var ratio = rect.width > 0 ? (event.clientX - rect.left) / rect.width : 0;
    ratio = Math.max(0, Math.min(1, ratio));
    return Math.round(ratio * (steps.length - 1));
  }

  function goToStep(index) {
    if (steps.length === 0) {
      return;
    }
    var clamped = Math.max(0, Math.min(steps.length - 1, index));
    if (clamped === currentIndex) {
      return;
    }
    currentIndex = clamped;
    renderStep();
  }

  function stepForward() {
    stopPlayback();
    if (steps.length === 0 || currentIndex >= steps.length - 1) {
      return;
    }
    goToStep(currentIndex + 1);
  }

  function stepBack() {
    stopPlayback();
    if (steps.length === 0 || currentIndex <= 0) {
      return;
    }
    goToStep(currentIndex - 1);
  }

  function togglePlay() {
    if (steps.length === 0) {
      return;
    }
    if (playTimer) {
      stopPlayback();
    } else {
      startPlayback();
    }
  }

  function startPlayback() {
    if (steps.length === 0 || currentIndex >= steps.length - 1) {
      return;
    }
    playPauseButton.classList.add('is-playing');
    playPauseButton.setAttribute('aria-pressed', 'true');
    playPauseButton.setAttribute('aria-label', 'Pause');
    playTimer = window.setInterval(function () {
      if (currentIndex >= steps.length - 1) {
        stopPlayback();
        return;
      }
      goToStep(currentIndex + 1);
    }, PLAY_INTERVAL_MS);
  }

  function stopPlayback() {
    if (playTimer) {
      window.clearInterval(playTimer);
      playTimer = null;
    }
    playPauseButton.classList.remove('is-playing');
    playPauseButton.setAttribute('aria-pressed', 'false');
    playPauseButton.setAttribute('aria-label', 'Play');
  }

  function renderStep() {
    updateCounterAndCaption();
    updateTicks();
    updateTransportState();

    if (isVectorTrace) {
      if (window.VectorSpace) {
        if (steps.length === 0) {
          window.VectorSpace.clear();
        } else {
          window.VectorSpace.highlightStep(steps, currentIndex, queryProjection);
        }
      }
      return;
    }

    if (window.GraphCanvas) {
      if (steps.length === 0) {
        window.GraphCanvas.clearStepHighlights();
      } else {
        window.GraphCanvas.highlightStep(steps, currentIndex);
      }
    }
    if (window.DriftTree) {
      if (steps.length === 0) {
        window.DriftTree.clear();
      } else {
        window.DriftTree.highlightStep(steps, currentIndex);
      }
    }
  }

  function updateCounterAndCaption() {
    var total = steps.length;
    if (stepCounterEl) {
      stepCounterEl.textContent = pad(total === 0 ? 0 : currentIndex + 1) + ' / ' + pad(total);
    }
    if (captionEl) {
      captionEl.textContent = loadError
          ? 'This Retrieval Trace could not be loaded. Please try again.'
          : total === 0
              ? 'Nothing was touched for this answer.'
              : captionFor(steps[currentIndex], currentIndex, total, cachedPassageFor(steps[currentIndex]));
      if (!loadError && total > 0) {
        enrichPassageCaption(steps, currentIndex);
      }
    }
    if (window.TracePane && !loadError && total > 0) {
      window.TracePane.highlight(currentIndex);
    }
  }

  function cachedPassageFor(step) {
    if (!step || step.kind !== 'TEXT_UNIT' || !traceCorpusId || !window.Passages) {
      return null;
    }
    return window.Passages.cached(traceCorpusId, step.identifier);
  }

  // A TEXT_UNIT caption first shows the plain "Read passage" fallback, then
  // names the passage once the shared passage cache resolves it — but only
  // if Replay is still showing that very step of that very trace.
  function enrichPassageCaption(stepList, index) {
    var step = stepList[index];
    if (!step || step.kind !== 'TEXT_UNIT' || !step.identifier || !traceCorpusId || !window.Passages
        || cachedPassageFor(step) || failedPassageIds[step.identifier]) {
      return;
    }
    var corpusId = traceCorpusId;
    window.Passages.load(corpusId, step.identifier)
      .then(function (passage) {
        if (steps === stepList && currentIndex === index && !loadError && captionEl) {
          captionEl.textContent = captionFor(step, index, stepList.length, passage);
          if (window.TracePane) {
            window.TracePane.highlight(index);
          }
        }
      })
      .catch(function () {
        // The fallback caption already shown stays.
        if (steps === stepList && traceCorpusId === corpusId) {
          failedPassageIds[step.identifier] = true;
        }
      });
  }

  // One short line for the bar: the full label and the reason for the step
  // live in the trace pane, so a long passage excerpt or a whole synthesized
  // answer never has to fit here.
  function captionFor(step, index, total, passage) {
    var prefix = 'Step ' + (index + 1) + ' / ' + total + ' — ';
    var label = clip(step.label, CAPTION_LABEL_CHARS);
    if (step.kind === 'TEXT_UNIT') {
      // Story 15.4: "Read passage {ordinal+1} of {documentName}" (Story
      // 13.4's "passage N" convention) once the passage is known, else the
      // plain fallback; the excerpt (the step label) follows either way.
      var head = passage && passage.documentName
          ? (typeof passage.ordinal === 'number'
              ? 'Read passage ' + (passage.ordinal + 1) + ' of ' + passage.documentName
              : 'Read passage of ' + passage.documentName)
          : 'Read passage';
      return prefix + head + (label ? ': ' + label : '');
    }
    var verb = step.kind === 'COMMUNITY' ? 'examined community'
        : step.kind === 'RELATIONSHIP' ? 'traversed relationship'
        : step.kind === 'SUB_QUESTION_SPAWNED' ? 'spawned sub-question'
        : step.kind === 'VECTOR_QUERY_EMBEDDED' ? 'embedded query'
        : step.kind === 'VECTOR_CHUNK' ? 'retrieved chunk'
        : step.kind === 'SYNTHESIS' ? 'synthesized answer'
        : 'matched entity';
    return prefix + verb + ' ' + label;
  }

  function clip(text, max) {
    var s = String(text == null ? '' : text);
    return s.length > max ? s.slice(0, max - 1) + '…' : s;
  }

  function updateTicks() {
    if (!tickTrack) {
      return;
    }
    var ticks = tickTrack.querySelectorAll('.replay-tick');
    for (var i = 0; i < ticks.length; i += 1) {
      var isNow = i === currentIndex;
      ticks[i].classList.toggle('is-done', i < currentIndex);
      ticks[i].classList.toggle('is-now', isNow);
      ticks[i].setAttribute('aria-current', isNow ? 'step' : 'false');
    }
  }

  function updateTransportState() {
    var total = steps.length;
    var hasSteps = total > 0;
    stepBackButton.disabled = !hasSteps || currentIndex <= 0;
    stepForwardButton.disabled = !hasSteps || currentIndex >= total - 1;
    playPauseButton.disabled = !hasSteps || currentIndex >= total - 1;
  }

  function pad(value) {
    var text = String(value);
    return text.length < 2 ? '0' + text : text;
  }
})();
