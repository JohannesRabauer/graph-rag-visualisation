(function () {
  'use strict';

  var fileInput = document.getElementById('corpus-file-input');
  var demoButton = document.getElementById('demo-dataset-button');
  var corpusChip = document.getElementById('corpus-chip');
  var errorBanner = document.getElementById('error-banner');
  var canvasIdle = document.getElementById('canvas-idle');
  var chatPanel = document.getElementById('chat-panel');
  var chatThread = document.getElementById('chat-thread');
  var chatForm = document.getElementById('chat-form');
  var chatInput = document.getElementById('chat-input');
  var sendButton = chatForm ? chatForm.querySelector('.send-button') : null;
  var modeButtons = document.querySelectorAll('.mode-button');
  var modeHint = document.getElementById('mode-hint');
  var communityToggleWrap = document.getElementById('community-toggle-wrap');
  var communityVisualizationToggle = document.getElementById('community-visualization-toggle');
  var graphCanvasEl = document.getElementById('graph-canvas');
  var graphEyebrow = document.getElementById('graph-eyebrow');
  var activeProgressSource = null;
  var activeCorpusId = null;
  var currentSearchMode = 'LOCAL';

  if (!fileInput || !corpusChip || !errorBanner) {
    return;
  }

  function setModeHint(mode) {
    currentSearchMode = mode;
    if (mode === 'GLOBAL') {
      modeHint.textContent = 'Global Search aggregates information across Communities to answer broader, corpus-level questions.';
    } else {
      modeHint.textContent = 'Local Search traverses specific Entities and Relationships around your question.';
    }

    modeButtons.forEach(function (button) {
      var isActive = button.dataset.mode === mode;
      button.classList.toggle('active', isActive);
      button.setAttribute('aria-pressed', isActive ? 'true' : 'false');
    });
  }

  modeButtons.forEach(function (button) {
    button.addEventListener('click', function () {
      setModeHint(button.dataset.mode || 'LOCAL');
    });
  });

  if (communityVisualizationToggle) {
    communityVisualizationToggle.addEventListener('change', function () {
      // Toggle affects only client-side rendering (AD-6) — the backend
      // stays unaware of this state and keeps emitting the same events
      // regardless. Nodes/edges stay visible either way; only the hull
      // overlay and its fold-in animation are toggle-controlled.
      if (window.GraphCanvas) {
        window.GraphCanvas.setHullsVisible(communityVisualizationToggle.checked);
      }
    });
  }

  if (chatForm) {
    chatForm.addEventListener('submit', function (event) {
      event.preventDefault();
      if (!activeCorpusId) {
        showErrorBanner('Choose a corpus before asking a question.');
        return;
      }

      var question = (chatInput ? chatInput.value : '').trim();
      if (!question) {
        showErrorBanner('Please enter a question first.');
        return;
      }

      appendMessage('question', question);
      if (chatInput) {
        chatInput.value = '';
      }
      hideErrorBanner();
      setQueryBusy(true);
      var pendingMessage = appendPendingMessage();

      var requestedSearchMode = currentSearchMode;

      fetch('/api/corpora/' + activeCorpusId + '/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: question, mode: requestedSearchMode })
      })
        .then(function (response) {
          return response.json().then(function (body) {
            return { ok: response.ok, body: body };
          });
        })
        .then(function (result) {
          if (result.ok) {
            // A "no Communities yet" response has no `answer`, only a
            // plain-language `reason` (AD-13's distinct noAnswer shape) —
            // prefer that over the generic "No answer was returned." fallback.
            // Use the mode that was actually sent with this request (not the
            // live global, which may have changed if the user toggled modes
            // while this request was in flight).
            appendAnswer(
                result.body.answer || result.body.reason,
                result.body.mode || requestedSearchMode,
                result.body.traceId,
                result.body.traceStepCount);
          } else {
            showErrorBanner(errorMessage(result.body));
          }
        })
        .catch(function () {
          showErrorBanner('The question could not be answered. Please try again.');
        })
        .finally(function () {
          removePendingMessage(pendingMessage);
          setQueryBusy(false);
        });
    });
  }

  if (demoButton) {
    var demoButtonDefaultLabel = demoButton.textContent;
    demoButton.addEventListener('click', function () {
      hideErrorBanner();
      fileInput.disabled = true;
      demoButton.disabled = true;
      demoButton.setAttribute('aria-busy', 'true');
      demoButton.textContent = 'Loading demo dataset…';

      fetch('/api/corpora/demo', {
        method: 'POST'
      })
        .then(function (response) {
          return response.json().then(function (body) {
            return { ok: response.ok, body: body };
          });
        })
        .then(function (result) {
          if (result.ok) {
            showCorpusChip(result.body);
          } else {
            showErrorBanner(errorMessage(result.body));
          }
        })
        .catch(function () {
          showErrorBanner('The demo dataset could not be loaded. Please try again.');
        })
        .finally(function () {
          fileInput.disabled = false;
          demoButton.disabled = false;
          demoButton.removeAttribute('aria-busy');
          demoButton.textContent = demoButtonDefaultLabel;
        });
    });
  }

  fileInput.addEventListener('change', function () {
    var files = fileInput.files;
    if (!files || files.length === 0) {
      return;
    }

    var formData = new FormData();
    for (var i = 0; i < files.length; i += 1) {
      formData.append('files', files[i]);
    }

    hideErrorBanner();
    fileInput.disabled = true;
    if (demoButton) {
      demoButton.disabled = true;
    }

    fetch('/api/corpora', {
      method: 'POST',
      body: formData
    })
      .then(function (response) {
        return response.json().then(function (body) {
          return { ok: response.ok, body: body };
        });
      })
      .then(function (result) {
        if (result.ok) {
          showCorpusChip(result.body);
        } else {
          showErrorBanner(errorMessage(result.body));
        }
      })
      .catch(function () {
        showErrorBanner('Upload failed. Please check your connection and try again.');
      })
      .finally(function () {
        fileInput.value = '';
        fileInput.disabled = false;
        if (demoButton) {
          demoButton.disabled = false;
        }
      });
  });

  function errorMessage(body) {
    return body && body.error ? body.error : 'Upload failed.';
  }

  function showCorpusChip(body) {
    // Close any still-open previous EventSource before touching GraphCanvas,
    // so a late event from a just-replaced corpus can't render into the
    // newly-initialized canvas.
    if (activeProgressSource) {
      activeProgressSource.close();
      activeProgressSource = null;
    }
    // Close any open Replay first too — otherwise a still-running autoplay
    // interval would keep calling GraphCanvas.highlightStep with a stale
    // trace's node ids against the canvas GraphCanvas.init() is about to
    // destroy and rebuild for this new corpus.
    if (window.Replay) {
      window.Replay.close();
    }

    var names = body && body.name ? body.name : (body.documentNames || []).join(', ');
    var count = body.documentCount || 0;
    var unit = count === 1 ? 'document' : 'documents';

    corpusChip.textContent = '';

    var dot = document.createElement('span');
    dot.className = 'status-dot';
    dot.setAttribute('aria-hidden', 'true');
    corpusChip.appendChild(dot);

    var label = document.createElement('span');
    label.textContent = names + ' · ' + count + ' ' + unit;
    corpusChip.appendChild(label);

    corpusChip.hidden = false;
    activeCorpusId = body && body.corpusId ? body.corpusId : activeCorpusId;
    if (canvasIdle) {
      canvasIdle.hidden = true;
    }
    if (chatPanel) {
      chatPanel.hidden = false;
    }
    if (communityToggleWrap) {
      communityToggleWrap.hidden = false;
    }
    if (communityVisualizationToggle) {
      communityVisualizationToggle.checked = true;
    }
    if (graphCanvasEl) {
      graphCanvasEl.hidden = false;
      graphCanvasEl.setAttribute('aria-hidden', 'false');
    }
    if (graphEyebrow) {
      graphEyebrow.hidden = false;
      setIngestionBusy(true);
    }
    if (window.GraphCanvas) {
      window.GraphCanvas.init();
      window.GraphCanvas.setHullsVisible(true);
    }
    connectProgressStream(body && body.corpusId);
  }

  // Busy indicator for the corpus-building phase: entity/relationship
  // extraction and community detection can take anywhere from a few
  // seconds to a couple of minutes with a real LLM behind them, and until
  // now the graph canvas gave no feedback beyond nodes trickling in one at
  // a time — this makes the "still working" state explicit via the
  // eyebrow's spinner + text, cleared on `ingestion-complete`/`error`.
  function setIngestionBusy(isBusy) {
    if (!graphEyebrow) {
      return;
    }
    graphEyebrow.textContent = isBusy ? 'Knowledge Graph — Building…' : 'Knowledge Graph — Live';
    graphEyebrow.classList.toggle('is-busy', isBusy);
    if (graphCanvasEl) {
      graphCanvasEl.setAttribute('aria-busy', isBusy ? 'true' : 'false');
    }
  }

  // Busy indicator for an in-flight query (Story-independent UX fix): the
  // send button swaps its icon for a spinner and both it and the input are
  // disabled so a second submit can't race the first while its answer is
  // still being computed (LOCAL/GLOBAL Search can take several seconds —
  // real LLM calls, not the old deterministic stub).
  function setQueryBusy(isBusy) {
    if (chatInput) {
      chatInput.disabled = isBusy;
    }
    if (sendButton) {
      sendButton.disabled = isBusy;
      if (isBusy) {
        sendButton.setAttribute('aria-busy', 'true');
      } else {
        sendButton.removeAttribute('aria-busy');
      }
    }
  }

  // A transient "Thinking…" bubble shown for the duration of the fetch —
  // removed (never left behind) once the real answer or an error arrives,
  // via `removePendingMessage` in the submit handler's `.finally`.
  function appendPendingMessage() {
    if (!chatThread) {
      return null;
    }
    var message = document.createElement('div');
    message.className = 'message answer pending';
    message.textContent = 'Thinking…';
    chatThread.appendChild(message);
    chatThread.scrollTop = chatThread.scrollHeight;
    return message;
  }

  function removePendingMessage(message) {
    if (message && message.parentNode) {
      message.parentNode.removeChild(message);
    }
  }

  function appendMessage(kind, text) {
    if (!chatThread) {
      return;
    }
    var message = document.createElement('div');
    message.className = 'message ' + kind;
    if (kind === 'answer') {
      var tag = document.createElement('div');
      tag.className = 'answer-tag';
      tag.textContent = (currentSearchMode === 'GLOBAL' ? 'Global Search' : 'Local Search') + ' · Answer';
      message.appendChild(tag);
      message.dataset.mode = currentSearchMode;
      var content = document.createElement('span');
      content.textContent = text;
      message.appendChild(content);
    } else {
      message.textContent = text;
    }
    chatThread.appendChild(message);
    chatThread.scrollTop = chatThread.scrollHeight;
  }

  function appendAnswer(text, mode, traceId, traceStepCount) {
    var activeMode = mode || currentSearchMode;
    var answerText = text || 'No answer was returned.';
    if (!chatThread) {
      return;
    }
    var message = document.createElement('div');
    message.className = 'message answer';
    message.dataset.mode = activeMode;

    var tag = document.createElement('div');
    tag.className = 'answer-tag';
    tag.textContent = (activeMode === 'GLOBAL' ? 'Global Search' : 'Local Search') + ' · Answer';
    message.appendChild(tag);

    var content = document.createElement('span');
    content.textContent = answerText;
    message.appendChild(content);

    // Story 3.3's own AC required this CTA on every answer message; it was
    // never shipped until Story 5.2 gave Replay something to open (Story
    // 5.1's `GET /api/traces/{traceId}`). `traceId` is always present on a
    // successful query response (LOCAL and GLOBAL, matched or not), so this
    // renders unconditionally rather than gating on a non-zero step count —
    // "— 0 steps" is itself a meaningful, plain-language answer.
    if (traceId) {
      var replayCta = document.createElement('button');
      replayCta.type = 'button';
      replayCta.className = 'replay-cta';
      replayCta.dataset.traceId = traceId;
      replayCta.dataset.stepCount = String(traceStepCount || 0);
      replayCta.textContent =
          'Replay this answer\'s Retrieval Trace — ' + (traceStepCount || 0) + ' steps';
      message.appendChild(replayCta);
    }

    chatThread.appendChild(message);
    chatThread.scrollTop = chatThread.scrollHeight;
  }

  function connectProgressStream(corpusId) {
    if (!corpusId || typeof EventSource === 'undefined') {
      return;
    }

    activeProgressSource = new EventSource('/api/corpora/' + corpusId + '/progress');
    activeProgressSource.addEventListener('heartbeat', function (event) {
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.message) {
          console.log('Progress heartbeat:', payload.data.message);
        }
      } catch (e) {
        console.warn('Invalid SSE heartbeat payload', e);
      }
    });

    activeProgressSource.addEventListener('ingestion-started', function (event) {
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.message) {
          console.log('Ingestion started:', payload.data.message);
        }
      } catch (e) {
        console.warn('Invalid SSE ingestion payload', e);
      }
    });

    activeProgressSource.addEventListener('entity-extracted', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        if (data && window.GraphCanvas) {
          window.GraphCanvas.addEntity(data.identity, data.name, data.type);
        }
      } catch (e) {
        console.warn('Invalid SSE entity-extracted payload', e);
      }
    });

    activeProgressSource.addEventListener('relationship-extracted', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        if (data && window.GraphCanvas) {
          window.GraphCanvas.addRelationship(
            data.sourceIdentity, data.source, data.targetIdentity, data.target, data.type);
        }
      } catch (e) {
        console.warn('Invalid SSE relationship-extracted payload', e);
      }
    });

    activeProgressSource.addEventListener('community-detected', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        if (data && window.GraphCanvas) {
          window.GraphCanvas.addCommunity(data.communityId, data.summary, data.memberEntityIdentities);
        }
      } catch (e) {
        console.warn('Invalid SSE community-detected payload', e);
      }
    });

    activeProgressSource.addEventListener('ingestion-complete', function () {
      setIngestionBusy(false);
    });

    activeProgressSource.addEventListener('error', function (event) {
      setIngestionBusy(false);
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.error) {
          showErrorBanner(payload.data.error);
        }
      } catch (e) {
        console.warn('Invalid SSE error payload', e);
      }
    });

    activeProgressSource.onerror = function () {
      activeProgressSource.close();
      activeProgressSource = null;
    };
  }

  function showErrorBanner(message) {
    errorBanner.textContent = message;
    errorBanner.hidden = false;
  }

  function hideErrorBanner() {
    errorBanner.hidden = true;
    errorBanner.textContent = '';
  }
})();
