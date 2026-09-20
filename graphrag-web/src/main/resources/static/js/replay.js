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

  if (!scrubber || !stepBackButton || !playPauseButton || !stepForwardButton) {
    return;
  }

  var steps = [];
  var currentIndex = 0;
  var playTimer = null;
  var dragging = false;

  // Delegated so it keeps working for every Replay CTA `upload.js` appends
  // later, without either file needing to know about the other's timing.
  document.addEventListener('click', function (event) {
    var cta = event.target && event.target.closest ? event.target.closest('.replay-cta') : null;
    if (!cta) {
      return;
    }
    openReplay(cta.dataset.traceId, parseInt(cta.dataset.stepCount, 10) || 0);
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
      stopPlayback();
      goToStep(nearestStepIndexForPointer(event));
    });
    tickTrack.addEventListener('pointermove', function (event) {
      if (!dragging) {
        return;
      }
      goToStep(nearestStepIndexForPointer(event));
    });
    window.addEventListener('pointerup', function () {
      dragging = false;
    });
  }

  function openReplay(traceId, stepCountHint) {
    if (!traceId) {
      return;
    }
    stopPlayback();

    fetch('/api/traces/' + traceId)
      .then(function (response) {
        return response.json().then(function (body) {
          return { ok: response.ok, body: body };
        });
      })
      .then(function (result) {
        if (!result.ok) {
          return;
        }
        steps = (result.body && result.body.steps) || [];
        currentIndex = 0;
        open();
        buildTicks();
        renderStep();
      })
      .catch(function () {
        // Replay is a best-effort, post-hoc view; a failed fetch just means
        // Replay doesn't open this time — the chat answer itself is
        // unaffected, so nothing more surfaces here (mirrors stepCountHint
        // only ever being a fallback display value, never load-bearing).
        void stepCountHint;
      });
  }

  function open() {
    scrubber.hidden = false;
    if (graphEyebrow) {
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
    if (window.GraphCanvas) {
      window.GraphCanvas.clearStepHighlights();
    }
  }

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

    if (window.GraphCanvas) {
      if (steps.length === 0) {
        window.GraphCanvas.clearStepHighlights();
      } else {
        window.GraphCanvas.highlightStep(steps, currentIndex);
      }
    }
  }

  function updateCounterAndCaption() {
    var total = steps.length;
    if (stepCounterEl) {
      stepCounterEl.textContent = pad(total === 0 ? 0 : currentIndex + 1) + ' / ' + pad(total);
    }
    if (captionEl) {
      captionEl.textContent = total === 0
          ? 'Nothing was touched for this answer.'
          : captionFor(steps[currentIndex], currentIndex, total);
    }
  }

  function captionFor(step, index, total) {
    var verb = step.kind === 'COMMUNITY' ? 'examined community'
        : step.kind === 'RELATIONSHIP' ? 'traversed relationship'
        : 'matched entity';
    return 'Step ' + (index + 1) + ' / ' + total + ' — ' + verb + ' ' + step.label;
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
