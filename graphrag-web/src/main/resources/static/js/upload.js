(function () {
  'use strict';

  var fileInput = document.getElementById('corpus-file-input');
  var demoButton = document.getElementById('demo-dataset-button');
  var demoOfflineButton = document.getElementById('demo-offline-button');
  var composerOfflineNote = document.getElementById('composer-offline-note');
  var corpusChip = document.getElementById('corpus-chip');
  var corpusHistoryToggle = document.getElementById('corpus-history-toggle');
  var corpusHistoryPopover = document.getElementById('corpus-history-popover');
  var corpusHistoryList = document.getElementById('corpus-history-list');
  var errorBanner = document.getElementById('error-banner');
  var canvasIdle = document.getElementById('canvas-idle');
  var chatPanel = document.getElementById('chat-panel');
  var chatThread = document.getElementById('chat-thread');
  var chatForm = document.getElementById('chat-form');
  var chatInput = document.getElementById('chat-input');
  var sendButton = chatForm ? chatForm.querySelector('.send-button') : null;
  var modeInputs = document.querySelectorAll('input[name="search-mode"]');
  var modeHint = document.getElementById('mode-hint');
  var communityToggleWrap = document.getElementById('community-toggle-wrap');
  var communityVisualizationToggle = document.getElementById('community-visualization-toggle');
  var entityTypeToggleWrap = document.getElementById('entity-type-toggle-wrap');
  var entityTypeColorToggle = document.getElementById('entity-type-color-toggle');
  var canvasSettingsToggle = document.getElementById('canvas-settings-toggle');
  var canvasSettingsPopover = document.getElementById('canvas-settings-popover');
  var canvasZoomControls = document.getElementById('canvas-zoom-controls');
  var canvasZoomInButton = document.getElementById('canvas-zoom-in-button');
  var canvasZoomOutButton = document.getElementById('canvas-zoom-out-button');
  var canvasZoomFitButton = document.getElementById('canvas-zoom-fit-button');
  var graphCanvasEl = document.getElementById('graph-canvas');
  var graphEyebrow = document.getElementById('graph-eyebrow');
  var workflowStatus = document.getElementById('workflow-status');
  var workflowStatusText = document.getElementById('workflow-status-text');
  var workflowRecoveryActions = document.getElementById('workflow-recovery-actions');
  var workflowRetryButton = document.getElementById('workflow-retry-button');
  var workflowRestartButton = document.getElementById('workflow-restart-button');
  var canvasTabBar = document.getElementById('canvas-tab-bar');
  var tabKnowledgeGraph = document.getElementById('tab-knowledge-graph');
  var tabVectorSpace = document.getElementById('tab-vector-space');
  var vectorSpacePanel = document.getElementById('vector-space-panel');
  var vectorSpaceAnswer = document.getElementById('vector-space-answer');
  var activeProgressSource = null;
  var activeCorpusId = null;
  var activeCorpusReady = false;
  var activeCorpusOffline = false;
  var currentSearchMode = 'LOCAL';

  // Entity detail panel (merged onto the main screen 2026-09-20 UX pass —
  // formerly the separate Explore page's own component, Story 6.2, which
  // fetched its graph in one bulk request). This screen builds its graph
  // incrementally from SSE events instead, so relationships a node click
  // needs to list are accumulated here as they arrive, not fetched.
  var entityDetailPanel = document.getElementById('entity-detail-panel');
  var entityDetailClose = document.getElementById('entity-detail-close');
  var entityDetailName = document.getElementById('entity-detail-name');
  var entityDetailType = document.getElementById('entity-detail-type');
  var entityDetailRelationships = document.getElementById('entity-detail-relationships');
  var entityDetailTags = document.getElementById('entity-detail-tags');
  var selectedEntityIdentity = null;
  // Story 10.3: the currently-open Entity's `type`, kept alongside
  // `selectedEntityIdentity` so a live toggle change can re-run
  // `renderEntityDetailTags` for the already-open panel without needing to
  // re-look-up the Entity's data.
  var selectedEntityType = null;
  var activeRelationships = [];

  if (!fileInput || !corpusChip || !errorBanner) {
    return;
  }

  function setModeHint(mode) {
    currentSearchMode = mode;
    if (mode === 'GLOBAL') {
      modeHint.textContent = 'Global Search aggregates information across Communities to answer broader, corpus-level questions.';
    } else if (mode === 'DRIFT') {
      modeHint.textContent = 'DRIFT runs a community pass, spawns targeted sub-questions, then re-ranks and synthesizes.';
    } else {
      modeHint.textContent = 'Local Search traverses specific Entities and Relationships around your question.';
    }
  }

  function answerTagLabel(mode) {
    if (mode === 'GLOBAL') {
      return 'Global Search · Answer';
    }
    if (mode === 'DRIFT') {
      return 'Drift Search · Answer';
    }
    return 'Local Search · Answer';
  }

  modeInputs.forEach(function (input) {
    input.addEventListener('change', function () {
      if (input.checked) {
        setModeHint(input.value || 'LOCAL');
      }
    });
  });

  // Browsers restore radio state on reload/back without firing `change`.
  function syncModeFromDom() {
    var checked = document.querySelector('input[name="search-mode"]:checked');
    setModeHint(checked ? (checked.value || 'LOCAL') : 'LOCAL');
  }
  syncModeFromDom();
  window.addEventListener('pageshow', syncModeFromDom);

  if (entityDetailClose) {
    entityDetailClose.addEventListener('click', function () {
      closeEntityDetailPanel();
    });
  }

  // Story 8-3: Compare CTA — delegated click handler.
  // Works for every .compare-cta button upload.js appends later, without
  // either needing to know about the other's timing (same pattern as
  // replay.js's delegated .replay-cta handler).
  document.addEventListener('click', function (event) {
    var cta = event.target && event.target.closest ? event.target.closest('.compare-cta') : null;
    if (!cta) {
      return;
    }
    var answerMsg = cta.closest('.message.answer');
    if (!answerMsg) {
      return;
    }
    var question = answerMsg.dataset.question;
    var corpusId = answerMsg.dataset.corpusId;
    if (!question || !corpusId) {
      return;
    }

    cta.disabled = true;
    cta.setAttribute('aria-busy', 'true');
    cta.textContent = 'Comparing\u2026';

    fetch('/api/corpora/' + corpusId + '/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: question, mode: 'VECTOR' })
    })
      .then(function (response) {
        return response.json().then(function (body) {
          return { ok: response.ok, body: body };
        });
      })
      .then(function (result) {
        if (result.ok) {
          var answerText = result.body.answer || result.body.reason || 'No answer was returned.';
          appendAnswer(answerText, result.body.mode || 'VECTOR',
              result.body.traceId, result.body.traceStepCount, null, result.body.queryProjection);
          revealVectorSpaceTab(answerText);
        } else {
          showErrorBanner(errorMessage(result.body));
        }
      })
      .catch(function () {
        showErrorBanner('The vector comparison could not be run. Please try again.');
      })
      .finally(function () {
        cta.disabled = false;
        cta.removeAttribute('aria-busy');
        cta.textContent = '\u21BB Compare with Vector Search';
      });
  });

  // spec-11-7 (#36): the settings popover collapses the community-formation
  // and entity-type-color toggles behind one small, constant-size button \u2014
  // an ever-growing absolute overlay competing with canvas content
  // otherwise. Opens/closes via the button, an outside click, or Escape;
  // never blocks the canvas while collapsed (the default state).
  function closeCanvasSettingsPopover() {
    if (!canvasSettingsPopover || canvasSettingsPopover.hidden) {
      return;
    }
    canvasSettingsPopover.hidden = true;
    if (canvasSettingsToggle) {
      canvasSettingsToggle.setAttribute('aria-expanded', 'false');
      // Return focus to the button that opened it — otherwise Escape,
      // an outside click, or the tab-switch auto-close would leave focus
      // stranded on a now-hidden control (or lost to the document body).
      canvasSettingsToggle.focus();
    }
  }

  function openCanvasSettingsPopover() {
    if (!canvasSettingsPopover || !canvasSettingsPopover.hidden) {
      return;
    }
    canvasSettingsPopover.hidden = false;
    if (canvasSettingsToggle) {
      canvasSettingsToggle.setAttribute('aria-expanded', 'true');
    }
    // Move focus into the popover's first control so keyboard users land
    // directly on something operable instead of the popover opening with
    // focus left behind on the button.
    var firstCheckbox = canvasSettingsPopover.querySelector('input[type="checkbox"]');
    if (firstCheckbox) {
      firstCheckbox.focus();
    }
  }

  if (canvasSettingsToggle) {
    canvasSettingsToggle.addEventListener('click', function () {
      if (canvasSettingsPopover && canvasSettingsPopover.hidden) {
        openCanvasSettingsPopover();
      } else {
        closeCanvasSettingsPopover();
      }
    });
  }

  // Story 11.6 (#35): zoom in/out/fit-to-view buttons — each just delegates
  // to `graph-canvas.js`'s own eased-animation functions; visibility is
  // gated the same way `#canvas-settings-toggle` is (corpus-ready reveal,
  // hidden again on reset), below.
  if (canvasZoomInButton) {
    canvasZoomInButton.addEventListener('click', function () {
      if (window.GraphCanvas) {
        window.GraphCanvas.zoomIn();
      }
    });
  }
  if (canvasZoomOutButton) {
    canvasZoomOutButton.addEventListener('click', function () {
      if (window.GraphCanvas) {
        window.GraphCanvas.zoomOut();
      }
    });
  }
  if (canvasZoomFitButton) {
    canvasZoomFitButton.addEventListener('click', function () {
      if (window.GraphCanvas) {
        window.GraphCanvas.fitToView();
      }
    });
  }

  // Delegated outside-click close \u2014 same pattern as the .compare-cta
  // handler above: a single document-level listener that no-ops unless the
  // popover is open and the click landed outside both the button and the
  // popover itself.
  document.addEventListener('click', function (event) {
    if (!canvasSettingsPopover || canvasSettingsPopover.hidden) {
      return;
    }
    var target = event.target;
    var withinPopover = target && target.closest && target.closest('#canvas-settings-popover');
    var onToggle = target && target.closest && target.closest('#canvas-settings-toggle');
    if (!withinPopover && !onToggle) {
      closeCanvasSettingsPopover();
    }
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') {
      closeCanvasSettingsPopover();
    }
  });

  // Story 8-3: reveal the Vector Space tab and update the answer panel.
  function revealVectorSpaceTab(answerText) {
    var wasHidden = !!(tabVectorSpace && tabVectorSpace.hidden);
    if (tabVectorSpace) {
      tabVectorSpace.removeAttribute('hidden');
    }
    if (vectorSpaceAnswer) {
      vectorSpaceAnswer.textContent = answerText || '';
    }
    // Story 8.5: fetch the corpus's settled chunk-scatter positions once —
    // VectorSpace.init() itself no-ops on a repeat call for the same corpus.
    if (window.VectorSpace && activeCorpusId) {
      window.VectorSpace.init(activeCorpusId);
    }
    // Auto-switch to Vector Space only the first time it is revealed — a
    // helpful nudge so the user sees their first comparison land, without
    // yanking them away from the Knowledge Graph tab on every subsequent
    // comparison (e.g. one that resolves after the user switched back).
    if (wasHidden) {
      switchCanvasTab('vector-space');
    }
  }

  // Story 8-3: tab switching.
  function switchCanvasTab(which) {
    var showVector = (which === 'vector-space');
    if (tabKnowledgeGraph) {
      tabKnowledgeGraph.setAttribute('aria-selected', showVector ? 'false' : 'true');
      tabKnowledgeGraph.tabIndex = showVector ? -1 : 0;
    }
    if (tabVectorSpace) {
      tabVectorSpace.setAttribute('aria-selected', showVector ? 'true' : 'false');
      tabVectorSpace.tabIndex = showVector ? 0 : -1;
    }
    // graph-canvas, drift-tree, replay-scrubber, entity-detail-panel —
    // all part of the Knowledge Graph tab surface.
    var kgEls = [
      document.getElementById('graph-canvas'),
      document.getElementById('drift-tree'),
      document.getElementById('graph-legend'),
      document.getElementById('graph-eyebrow'),
      document.getElementById('canvas-settings-toggle'),
      document.getElementById('canvas-zoom-controls'),
      document.getElementById('replay-scrubber'),
      document.getElementById('entity-detail-panel'),
      document.getElementById('entity-search')
    ];
    if (showVector) {
      // The settings button hides below (part of kgEls) — always close its
      // popover too, so it never lingers open-but-invisible behind the
      // Vector Space tab and reappears already-open when switching back.
      closeCanvasSettingsPopover();
    }
    kgEls.forEach(function (el) {
      if (!el) {
        return;
      }
      // Only toggle elements that are already visible (not ones that are
      // hidden for their own reasons, e.g. canvas-idle or workflow-status).
      if (showVector) {
        if (!el.hidden) {
          el.dataset.hiddenByTabSwitch = '1';
          el.hidden = true;
        }
      } else {
        if (el.dataset.hiddenByTabSwitch === '1') {
          el.hidden = false;
          delete el.dataset.hiddenByTabSwitch;
        }
      }
    });
    if (vectorSpacePanel) {
      vectorSpacePanel.hidden = !showVector;
    }
  }

  if (tabKnowledgeGraph) {
    tabKnowledgeGraph.addEventListener('click', function () {
      switchCanvasTab('knowledge-graph');
    });
    tabKnowledgeGraph.addEventListener('keydown', function (event) {
      if (event.key === 'ArrowRight' && tabVectorSpace && !tabVectorSpace.hidden) {
        event.preventDefault();
        tabVectorSpace.focus();
        switchCanvasTab('vector-space');
      } else if (event.key === 'End' && tabVectorSpace && !tabVectorSpace.hidden) {
        event.preventDefault();
        tabVectorSpace.focus();
        switchCanvasTab('vector-space');
      }
    });
  }

  if (tabVectorSpace) {
    tabVectorSpace.addEventListener('click', function () {
      switchCanvasTab('vector-space');
    });
    tabVectorSpace.addEventListener('keydown', function (event) {
      if (event.key === 'ArrowLeft') {
        event.preventDefault();
        tabKnowledgeGraph.focus();
        switchCanvasTab('knowledge-graph');
      } else if (event.key === 'Home') {
        event.preventDefault();
        tabKnowledgeGraph.focus();
        switchCanvasTab('knowledge-graph');
      }
    });
  }

  // Story 8-3: expose tab switching so other modules (replay.js) can bring
  // the Knowledge Graph tab back into view when they open — the Knowledge
  // Graph surface (graph-canvas, drift-tree, replay-scrubber) and the
  // Vector Space panel must stay mutually exclusive regardless of which
  // module triggers the transition.
  window.CanvasTabs = {
    switchTo: switchCanvasTab
  };

  function closeEntityDetailPanel() {
    selectedEntityIdentity = null;
    selectedEntityType = null;
    if (!entityDetailPanel) {
      return;
    }
    entityDetailPanel.classList.remove('is-open');
    entityDetailPanel.setAttribute('aria-hidden', 'true');
  }

  // Builds one line per Relationship involving `identity`, matching on
  // `sourceIdentity`/`targetIdentity` against `activeRelationships` (built
  // up as `relationship-extracted` SSE events arrive — no separate fetch).
  function relationshipLines(identity) {
    var lines = [];
    activeRelationships.forEach(function (relationship) {
      var isSource = relationship.sourceIdentity === identity;
      var isTarget = relationship.targetIdentity === identity;
      if (!isSource && !isTarget) {
        return;
      }
      var otherName = isSource ? relationship.target : relationship.source;
      var relationshipType = relationship.type || 'related_to';
      lines.push(isSource
          ? '→ ' + relationshipType + ' → ' + otherName
          : '← ' + relationshipType + ' ← ' + otherName);
    });
    return lines;
  }

  function renderEntityDetailRelationships(identity) {
    if (!entityDetailRelationships) {
      return;
    }
    entityDetailRelationships.textContent = '';
    var lines = relationshipLines(identity);
    if (lines.length === 0) {
      var empty = document.createElement('li');
      empty.className = 'node-detail-relationships-empty';
      empty.textContent = 'No relationships';
      entityDetailRelationships.appendChild(empty);
      return;
    }
    lines.forEach(function (line) {
      var item = document.createElement('li');
      item.textContent = line;
      entityDetailRelationships.appendChild(item);
    });
  }

  function renderEntityDetailTags(type) {
    if (!entityDetailTags) {
      return;
    }
    entityDetailTags.textContent = '';
    // Always exactly one chip — the Entity's own `type` value stands in for
    // a Tag (human decision; no real Tag concept exists — see Design Notes).
    var chip = document.createElement('span');
    chip.className = 'node-detail-tag';
    chip.textContent = type || 'Unknown';
    // Story 10.3: color the chip to match its Entity's canvas node — same
    // deterministic-hash color, from the single source of truth
    // (`window.GraphCanvas.entityTypeColors`), only when the toggle is ON.
    // Guarded on a truthy `type`: a placeholder node (`ensureNode`) has no
    // type yet and its canvas node is never touched by `addEntity`, so
    // coloring the chip from a hash of "" here would break chip/node
    // parity — falls through to the neutral style instead, matching the
    // node's own untouched neutral rendering.
    // Text color is deliberately left at `.node-detail-tag`'s neutral
    // `--ink-900` (never set to `colors.labelColor`) — that pairing's
    // contrast against the deeper/dustier fill tokens is too low to read.
    if (type && entityTypeColorToggle && entityTypeColorToggle.checked && window.GraphCanvas) {
      var colors = window.GraphCanvas.entityTypeColors(type);
      if (colors) {
        chip.style.background = colors.fill;
        chip.style.borderColor = colors.labelColor;
        chip.style.color = '';
      }
    } else {
      chip.style.background = '';
      chip.style.borderColor = '';
      chip.style.color = '';
    }
    entityDetailTags.appendChild(chip);
  }

  function openEntityDetailPanel(nodeData) {
    if (!entityDetailPanel) {
      return;
    }
    selectedEntityIdentity = nodeData.identity;
    selectedEntityType = nodeData.type;
    if (entityDetailName) {
      entityDetailName.textContent = nodeData.name || nodeData.identity;
    }
    if (entityDetailType) {
      entityDetailType.textContent = 'Type: ' + (nodeData.type || 'Unknown');
    }
    renderEntityDetailRelationships(nodeData.identity);
    renderEntityDetailTags(nodeData.type);
    entityDetailPanel.classList.add('is-open');
    entityDetailPanel.setAttribute('aria-hidden', 'false');
  }

  // Registered once, module-level — GraphCanvas stores tap callbacks as
  // module state, not re-wired per `init()` call, so this survives every
  // corpus load without needing to be re-registered (graph-canvas.js).
  if (window.GraphCanvas && typeof window.GraphCanvas.onNodeTap === 'function') {
    window.GraphCanvas.onNodeTap(function (nodeData) {
      if (!nodeData || !nodeData.identity) {
        return;
      }
      if (selectedEntityIdentity === nodeData.identity) {
        closeEntityDetailPanel();
        return;
      }
      openEntityDetailPanel(nodeData);
    });
  }

  if (window.GraphCanvas && typeof window.GraphCanvas.onBackgroundTap === 'function') {
    window.GraphCanvas.onBackgroundTap(function () {
      closeEntityDetailPanel();
    });
  }

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

  if (entityTypeColorToggle) {
    entityTypeColorToggle.addEventListener('change', function () {
      // Story 10.3: also client-side rendering only, same convention as
      // the community-visualization toggle above. Recolors every already-
      // rendered node immediately, and re-renders the open panel's Tag
      // chip (if any) so it doesn't need to be re-opened to reflect the
      // new state.
      if (window.GraphCanvas) {
        window.GraphCanvas.setEntityTypeColoringEnabled(entityTypeColorToggle.checked);
      }
      if (selectedEntityIdentity) {
        renderEntityDetailTags(selectedEntityType);
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
      if (activeCorpusOffline) {
        showErrorBanner('This is the offline demo corpus — its questions are pre-recorded, not live. '
            + 'Load a live corpus to ask your own.');
        return;
      }
      if (!activeCorpusReady) {
        showErrorBanner('The graph is still building. Wait for “Knowledge Graph — Ready” before submitting.');
        renderWorkflowStatus('BUILDING');
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
      var requestedCorpusId = activeCorpusId;

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
          // Stale response guard: a restart-and-confirm (or a second Corpus
          // load) while this fetch was in flight must not append its answer
          // into a thread that has since been reset or moved on.
          if (requestedCorpusId !== activeCorpusId) {
            return;
          }
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
                result.body.traceStepCount,
                question);
          } else {
            showErrorBanner(errorMessage(result.body));
          }
        })
        .catch(function () {
          if (requestedCorpusId !== activeCorpusId) {
            return;
          }
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
      if (demoOfflineButton) {
        demoOfflineButton.disabled = true;
      }

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
            if (corpusHistoryToggle) {
              corpusHistoryToggle.hidden = false;
            }
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
          if (demoOfflineButton) {
            demoOfflineButton.disabled = false;
          }
        });
    });
  }

  // Story 9.1: the offline demo — same shape as the live Demo Dataset
  // button, a different endpoint. Left as plain disabled/aria-busy state
  // rather than swapping textContent, since this button's label has its
  // own "Offline" badge markup that a text swap would destroy.
  if (demoOfflineButton) {
    demoOfflineButton.addEventListener('click', function () {
      hideErrorBanner();
      fileInput.disabled = true;
      demoOfflineButton.disabled = true;
      demoOfflineButton.setAttribute('aria-busy', 'true');
      if (demoButton) {
        demoButton.disabled = true;
      }

      fetch('/api/corpora/demo-offline', {
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
            if (corpusHistoryToggle) {
              corpusHistoryToggle.hidden = false;
            }
          } else {
            showErrorBanner(errorMessage(result.body));
          }
        })
        .catch(function () {
          showErrorBanner('The offline demo could not be loaded. Please try again.');
        })
        .finally(function () {
          fileInput.disabled = false;
          demoOfflineButton.disabled = false;
          demoOfflineButton.removeAttribute('aria-busy');
          if (demoButton) {
            demoButton.disabled = false;
          }
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
    if (demoOfflineButton) {
      demoOfflineButton.disabled = true;
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
          if (corpusHistoryToggle) {
            corpusHistoryToggle.hidden = false;
          }
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
        if (demoOfflineButton) {
          demoOfflineButton.disabled = false;
        }
      });
  });

  function errorMessage(body) {
    return body && body.error ? body.error : 'Upload failed.';
  }

  if (workflowRetryButton) {
    workflowRetryButton.addEventListener('click', function () {
      if (!activeCorpusId) {
        return;
      }
      hideErrorBanner();
      renderWorkflowStatus('BUILDING');
      connectProgressStream(activeCorpusId);
    });
  }

  if (workflowRestartButton) {
    workflowRestartButton.addEventListener('click', function () {
      if (!window.confirm('Loading a new Corpus will discard the current one — continue?')) {
        return;
      }
      resetToIdleState();
    });
  }

  // spec-10-4 (GitHub #23): tears down every piece of active-corpus state
  // `showCorpusChip` turns on, then re-reveals #canvas-idle — the mirror
  // image of `showCorpusChip` below. After this runs, the existing,
  // unmodified upload/demo-dataset handlers just work again for a second
  // Corpus, since they already call `showCorpusChip` unconditionally on
  // success.
  function resetToIdleState() {
    hideErrorBanner();

    if (activeProgressSource) {
      activeProgressSource.close();
      activeProgressSource = null;
    }
    if (window.Replay) {
      window.Replay.close();
    }
    closeEntityDetailPanel();
    activeRelationships = [];

    activeCorpusId = null;
    activeCorpusReady = false;
    activeCorpusOffline = false;

    setModeHint('LOCAL');
    modeInputs.forEach(function (input) {
      input.checked = (input.value === 'LOCAL');
    });

    if (chatThread) {
      chatThread.textContent = '';
    }
    if (chatInput) {
      chatInput.value = '';
      chatInput.disabled = false;
      chatInput.placeholder = 'Ask a question about the Corpus…';
    }
    if (sendButton) {
      sendButton.disabled = false;
    }
    if (composerOfflineNote) {
      composerOfflineNote.hidden = true;
    }

    // switchCanvasTab('knowledge-graph') must run BEFORE the explicit hides
    // below: if Vector Space was ever revealed this session, its own
    // cleanup un-hides (`el.hidden = false`) any element still carrying
    // `dataset.hiddenByTabSwitch` from that earlier switch — running it
    // after would silently undo the hides this function is about to apply.
    switchCanvasTab('knowledge-graph');
    if (tabVectorSpace) {
      tabVectorSpace.setAttribute('hidden', '');
    }
    if (vectorSpacePanel) {
      vectorSpacePanel.hidden = true;
    }
    if (vectorSpaceAnswer) {
      vectorSpaceAnswer.textContent = '';
    }

    if (corpusChip) {
      corpusChip.hidden = true;
    }
    if (workflowRestartButton) {
      workflowRestartButton.hidden = true;
    }
    if (chatPanel) {
      chatPanel.hidden = true;
    }
    if (communityToggleWrap) {
      communityToggleWrap.hidden = true;
    }
    if (entityTypeToggleWrap) {
      entityTypeToggleWrap.hidden = true;
    }
    if (canvasSettingsToggle) {
      canvasSettingsToggle.hidden = true;
    }
    if (canvasZoomControls) {
      canvasZoomControls.hidden = true;
    }
    closeCanvasSettingsPopover();
    if (graphCanvasEl) {
      graphCanvasEl.hidden = true;
      graphCanvasEl.setAttribute('aria-hidden', 'true');
    }
    if (canvasTabBar) {
      canvasTabBar.hidden = true;
    }
    // Story 12.7: corpusHistoryToggle is deliberately left visible here —
    // the corpus-history switcher should stay reachable from the idle
    // screen too, so a returning user can jump back into a prior corpus.
    var entitySearchEl = document.getElementById('entity-search');
    if (entitySearchEl) {
      entitySearchEl.hidden = true;
    }
    var entitySearchInputEl = document.getElementById('entity-search-input');
    if (entitySearchInputEl) {
      entitySearchInputEl.value = '';
    }
    if (window.EntitySearch) {
      window.EntitySearch.close();
    }
    if (graphEyebrow) {
      graphEyebrow.hidden = true;
    }
    if (workflowStatus) {
      workflowStatus.hidden = true;
    }

    if (window.GraphCanvas) {
      // Destroys/recreates `cy` (graph-canvas.js's own init already calls
      // cy.destroy() internally), clearing the legend, so the canvas is
      // genuinely empty even before a new Corpus is chosen.
      window.GraphCanvas.init({ interactive: true });
    }

    if (fileInput) {
      fileInput.disabled = false;
      fileInput.value = '';
    }
    if (demoButton) {
      demoButton.disabled = false;
    }
    if (demoOfflineButton) {
      demoOfflineButton.disabled = false;
    }

    if (canvasIdle) {
      canvasIdle.hidden = false;
    }
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
    // A detail panel open for the previous corpus's Entity would otherwise
    // keep showing stale content (or a selectedEntityIdentity that no
    // longer resolves to anything) once the canvas is rebuilt below.
    closeEntityDetailPanel();
    activeRelationships = [];

    // Story 9.1: the offline demo corpus never accepts a new question — the
    // note replaces the composer placeholder for the whole time it's active.
    activeCorpusOffline = !!(body && body.offline);
    if (composerOfflineNote) {
      composerOfflineNote.hidden = !activeCorpusOffline;
    }
    if (chatInput) {
      chatInput.disabled = activeCorpusOffline;
      chatInput.placeholder = activeCorpusOffline
          ? 'Questions are pre-recorded for the offline demo'
          : 'Ask a question about the Corpus…';
    }
    if (sendButton) {
      sendButton.disabled = activeCorpusOffline;
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
    if (workflowRestartButton) {
      workflowRestartButton.hidden = false;
    }
    activeCorpusId = body && body.corpusId ? body.corpusId : activeCorpusId;
    activeCorpusReady = false;
    renderWorkflowStatus('BUILDING');
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
    if (entityTypeToggleWrap) {
      entityTypeToggleWrap.hidden = false;
    }
    if (entityTypeColorToggle) {
      entityTypeColorToggle.checked = true;
    }
    if (canvasSettingsToggle) {
      canvasSettingsToggle.hidden = false;
    }
    if (canvasZoomControls) {
      canvasZoomControls.hidden = false;
    }
    if (graphCanvasEl) {
      graphCanvasEl.hidden = false;
      graphCanvasEl.setAttribute('aria-hidden', 'false');
    }
    if (canvasTabBar) {
      canvasTabBar.hidden = false;
    }
    var entitySearchEl = document.getElementById('entity-search');
    if (entitySearchEl) {
      entitySearchEl.hidden = false;
    }
    if (graphEyebrow) {
      graphEyebrow.hidden = false;
      setIngestionBusy(true);
    }
    if (window.GraphCanvas) {
      // Interactive (pan/zoom + node-click detail panel) since the former
      // separate Explore page's canvas capabilities merged onto this one
      // (2026-09-20 UX pass) — the main screen no longer defers those to a
      // second page.
      window.GraphCanvas.init({ interactive: true });
      window.GraphCanvas.setHullsVisible(true);
      window.GraphCanvas.setEntityTypeColoringEnabled(true);
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

  function appendAnswer(text, mode, traceId, traceStepCount, question, queryProjection) {
    var activeMode = mode || currentSearchMode;
    var answerText = text || 'No answer was returned.';
    if (!chatThread) {
      return;
    }
    var message = document.createElement('div');
    message.className = 'message answer';
    message.dataset.mode = activeMode;
    // Store question and corpusId so the Compare CTA handler can retrieve them
    // without closing over stale values.
    if (question) {
      message.dataset.question = question;
    }
    if (activeCorpusId) {
      message.dataset.corpusId = activeCorpusId;
    }

    var tag = document.createElement('div');
    tag.className = 'answer-tag';
    tag.textContent = answerTagLabel(activeMode);
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
      // Story 8.5: a VECTOR answer's Replay CTA also carries the query's own
      // 2D projection — the trace steps themselves only carry chunk
      // ids/scores, not coordinates, so replay.js reads this back off the
      // button it clicked rather than needing a second fetch.
      if (activeMode === 'VECTOR' && queryProjection) {
        replayCta.dataset.queryProjection = JSON.stringify(queryProjection);
      }
      replayCta.textContent =
          'Replay this answer\'s Retrieval Trace — ' + (traceStepCount || 0) + ' steps';
      message.appendChild(replayCta);
    }

    // Story 8-3: Compare CTA on LOCAL / GLOBAL / DRIFT answers only —
    // never on VECTOR answers themselves (no nesting).
    if (activeMode !== 'VECTOR' && question && activeCorpusId) {
      var compareCta = document.createElement('button');
      compareCta.type = 'button';
      compareCta.className = 'compare-cta';
      var compareCtaExplanation = 'Re-runs this question through a plain vector-similarity search ' +
          '(no knowledge graph) for comparison, and opens the Vector Space tab showing ' +
          'that answer and where the corpus\'s chunks sit in embedding space.';
      compareCta.title = compareCtaExplanation;
      compareCta.setAttribute('aria-label', compareCtaExplanation);
      compareCta.textContent = '\u21BB Compare with Vector Search';
      message.appendChild(compareCta);
    }

    chatThread.appendChild(message);
    chatThread.scrollTop = chatThread.scrollHeight;
  }

  function connectProgressStream(corpusId) {
    if (!corpusId || typeof EventSource === 'undefined') {
      return;
    }
    if (activeProgressSource) {
      activeProgressSource.close();
      activeProgressSource = null;
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
          // Accumulated for the entity detail panel's relationship list —
          // the main screen builds its graph incrementally from these SSE
          // events rather than one bulk fetch, so there's nothing else to
          // read a node's relationships from.
          activeRelationships.push(data);
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
      activeCorpusReady = true;
      renderWorkflowStatus('READY');
    });

    activeProgressSource.addEventListener('error', function (event) {
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.error) {
          setIngestionBusy(false);
          activeCorpusReady = false;
          showErrorBanner(payload.data.error);
          renderWorkflowStatus('FAILED', payload.data.error);
        }
      } catch (e) {
        // Transport-level EventSource errors also use the "error" event name
        // but do not carry our structured SSE payload.
      }
    });

    // Capture the specific EventSource instance this handler was attached
    // to (not the shared module-level `activeProgressSource`) — a restart
    // (resetToIdleState) may already have closed and nulled that shared
    // reference by the time this fires, and a stale handler must then be a
    // complete no-op rather than re-showing a banner for a discarded
    // Corpus or throwing on `null.close()`.
    var source = activeProgressSource;
    source.onerror = function () {
      if (activeProgressSource !== source) {
        return;
      }
      if (!activeCorpusReady) {
        showErrorBanner('The progress stream disconnected. You can reconnect it or start over with a new corpus.');
      }
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

  function renderWorkflowStatus(state, failureMessage) {
    if (!workflowStatus || !workflowStatusText) {
      return;
    }
    if (state === 'READY') {
      // Once ready, the graph-eyebrow's own "Knowledge Graph — Live" label
      // (top-left) already carries this visually — showing this banner too
      // would float a second status box over the canvas/legend/toggle with
      // nothing to visually anchor it to. Keep the announcement for
      // screen-reader users (this element is `aria-live="polite"`) without
      // rendering a floating box on top of live graph content.
      workflowStatus.hidden = false;
      workflowStatus.classList.add('workflow-status--announce-only');
      workflowStatusText.textContent = 'Knowledge Graph — Ready. Ask a LOCAL, GLOBAL, or DRIFT question now.';
      if (workflowRecoveryActions) {
        workflowRecoveryActions.hidden = true;
      }
      return;
    }
    workflowStatus.classList.remove('workflow-status--announce-only');
    if (state === 'FAILED') {
      workflowStatus.hidden = false;
      workflowStatusText.textContent = failureMessage || 'Knowledge Graph build failed. Retry stream or restart with a new corpus.';
      if (workflowRecoveryActions) {
        workflowRecoveryActions.hidden = false;
      }
      return;
    }
    workflowStatus.hidden = false;
    workflowStatusText.textContent = 'Knowledge Graph — Building. Questions stay disabled until ingestion completes.';
    if (workflowRecoveryActions) {
      workflowRecoveryActions.hidden = true;
    }
  }

  // ---------------------------------------------------------------------
  // Story 12.7: corpus history switcher + auto-restore on load.
  //
  // `activateCorpus` is a third whole-state-change path alongside
  // `showCorpusChip` (fresh ingestion) and `resetToIdleState` (start over,
  // Story 10.4) — reselecting a *previously*-ingested corpus without
  // re-ingesting or reloading the page. Neither of those two functions is
  // modified: this shares their teardown *steps* (close EventSource/Replay,
  // close the entity detail panel, clear `activeRelationships`) via its own
  // copy of that short sequence, rather than refactoring either of them.
  // ---------------------------------------------------------------------

  function renderCorpusChipFromHistory(corpusMeta) {
    if (!corpusChip) {
      return;
    }
    corpusChip.textContent = '';

    var dot = document.createElement('span');
    dot.className = 'status-dot';
    dot.setAttribute('aria-hidden', 'true');
    corpusChip.appendChild(dot);

    var label = document.createElement('span');
    label.textContent = corpusMeta.name || corpusMeta.id;
    corpusChip.appendChild(label);

    corpusChip.hidden = false;
  }

  // Same reveal set `showCorpusChip` applies to the graph-canvas surface —
  // duplicated here (not extracted) so `showCorpusChip` itself stays
  // untouched.
  function revealCorpusCanvasSurface() {
    if (chatPanel) {
      chatPanel.hidden = false;
    }
    if (communityToggleWrap) {
      communityToggleWrap.hidden = false;
    }
    if (communityVisualizationToggle) {
      communityVisualizationToggle.checked = true;
    }
    if (entityTypeToggleWrap) {
      entityTypeToggleWrap.hidden = false;
    }
    if (entityTypeColorToggle) {
      entityTypeColorToggle.checked = true;
    }
    if (canvasSettingsToggle) {
      canvasSettingsToggle.hidden = false;
    }
    if (canvasZoomControls) {
      canvasZoomControls.hidden = false;
    }
    if (graphCanvasEl) {
      graphCanvasEl.hidden = false;
      graphCanvasEl.setAttribute('aria-hidden', 'false');
    }
    if (canvasTabBar) {
      canvasTabBar.hidden = false;
    }
    var entitySearchEl = document.getElementById('entity-search');
    if (entitySearchEl) {
      entitySearchEl.hidden = false;
    }
    if (graphEyebrow) {
      graphEyebrow.hidden = false;
    }
    if (window.GraphCanvas) {
      window.GraphCanvas.init({ interactive: true });
      window.GraphCanvas.setHullsVisible(true);
      window.GraphCanvas.setEntityTypeColoringEnabled(true);
    }
  }

  /**
   * Reselects a corpus already known to the durable registry
   * (`GET /api/corpora`'s row shape: `id`/`name`/`status`/`createdAt`/
   * `lastActivatedAt`). Branches on `status`:
   *  - `BUILDING`: reconnects the existing live progress stream, resuming
   *    to watch it finish — never re-ingests.
   *  - `READY`: bulk-fetches `GET /api/corpora/{id}/graph` (same payload
   *    shapes as the `entity-extracted`/`relationship-extracted`/
   *    `community-detected` SSE events) and loops the same
   *    `GraphCanvas.addEntity`/`addRelationship`/`addCommunity` functions
   *    the live SSE handlers already call.
   *  - `FAILED`: renders the existing failure/recovery UI, no graph.
   *
   * Never tears down to `#canvas-idle` — that stays owned by
   * `resetToIdleState` alone. Does not itself call
   * `POST /api/corpora/{id}/activate` — both call sites (the page-load
   * bootstrap and the switcher's row click handler) do that themselves.
   */
  function activateCorpus(corpusMeta) {
    if (!corpusMeta || !corpusMeta.id) {
      return;
    }
    hideErrorBanner();

    // Shared teardown steps (see comment above) — stop any previous
    // corpus's live activity before switching.
    if (activeProgressSource) {
      activeProgressSource.close();
      activeProgressSource = null;
    }
    if (window.Replay) {
      window.Replay.close();
    }
    closeEntityDetailPanel();
    activeRelationships = [];

    activeCorpusId = corpusMeta.id;
    activeCorpusReady = false;
    activeCorpusOffline = false;
    if (composerOfflineNote) {
      composerOfflineNote.hidden = true;
    }
    if (chatInput) {
      chatInput.disabled = false;
      chatInput.placeholder = 'Ask a question about the Corpus…';
    }
    if (sendButton) {
      sendButton.disabled = false;
    }
    if (chatThread) {
      chatThread.textContent = '';
    }

    renderCorpusChipFromHistory(corpusMeta);
    if (workflowRestartButton) {
      workflowRestartButton.hidden = false;
    }
    if (corpusHistoryToggle) {
      corpusHistoryToggle.hidden = false;
    }
    if (canvasIdle) {
      canvasIdle.hidden = true;
    }

    // Preserves resetToIdleState's documented ordering constraint:
    // switchCanvasTab('knowledge-graph') must run BEFORE the explicit
    // Vector Space hides below — if Vector Space was ever revealed for the
    // previous corpus, its own tab-switch cleanup would otherwise silently
    // re-show whatever this switch is about to hide.
    switchCanvasTab('knowledge-graph');
    if (tabVectorSpace) {
      tabVectorSpace.setAttribute('hidden', '');
    }
    if (vectorSpacePanel) {
      vectorSpacePanel.hidden = true;
    }
    if (vectorSpaceAnswer) {
      vectorSpaceAnswer.textContent = '';
    }

    revealCorpusCanvasSurface();

    if (corpusMeta.status === 'BUILDING') {
      setIngestionBusy(true);
      renderWorkflowStatus('BUILDING');
      connectProgressStream(corpusMeta.id);
      return;
    }

    if (corpusMeta.status === 'FAILED') {
      setIngestionBusy(false);
      renderWorkflowStatus('FAILED', 'Graph construction failed for this corpus. Retry stream or restart with a new corpus.');
      return;
    }

    if (corpusMeta.status === 'READY') {
      // READY: bulk-load once via the new endpoint, replaying the same
      // payload shapes the SSE handlers already know how to consume — no new
      // GraphCanvas API (Design Notes).
      setIngestionBusy(true);
      renderWorkflowStatus('BUILDING');
      var requestedCorpusId = corpusMeta.id;

      fetch('/api/corpora/' + corpusMeta.id + '/graph')
        .then(function (response) {
          if (!response.ok) {
            throw new Error('Failed to load corpus graph');
          }
          return response.json();
        })
        .then(function (body) {
          // Stale response guard: a second switch while this fetch was in
          // flight must not render into a canvas that has since moved on.
          if (requestedCorpusId !== activeCorpusId) {
            return;
          }
          if (window.GraphCanvas) {
            (body.entities || []).forEach(function (entity) {
              window.GraphCanvas.addEntity(entity.identity, entity.name, entity.type);
            });
            (body.relationships || []).forEach(function (relationship) {
              window.GraphCanvas.addRelationship(
                  relationship.sourceIdentity, relationship.source,
                  relationship.targetIdentity, relationship.target, relationship.type);
              activeRelationships.push(relationship);
            });
            (body.communities || []).forEach(function (community) {
              window.GraphCanvas.addCommunity(
                  community.communityId, community.summary, community.memberEntityIdentities);
            });
          }
          setIngestionBusy(false);
          activeCorpusReady = true;
          renderWorkflowStatus('READY');
        })
        .catch(function () {
          if (requestedCorpusId !== activeCorpusId) {
            return;
          }
          setIngestionBusy(false);
          showErrorBanner('The corpus graph could not be loaded. Please try again.');
        });
      return;
    }

    console.warn('Unknown corpus status: ' + corpusMeta.status);
  }

  function closeCorpusHistoryPopover() {
    if (!corpusHistoryPopover || corpusHistoryPopover.hidden) {
      return;
    }
    corpusHistoryPopover.hidden = true;
    if (corpusHistoryToggle) {
      corpusHistoryToggle.setAttribute('aria-expanded', 'false');
    }
  }

  function renderCorpusHistoryRows(corpora) {
    if (!corpusHistoryList) {
      return;
    }
    corpusHistoryList.textContent = '';

    if (!corpora || corpora.length === 0) {
      var empty = document.createElement('li');
      empty.className = 'corpus-history-empty';
      empty.textContent = 'No previous corpora yet.';
      corpusHistoryList.appendChild(empty);
      return;
    }

    corpora.forEach(function (corpusMeta) {
      var row = document.createElement('li');
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'corpus-history-row';
      if (corpusMeta.id === activeCorpusId) {
        button.classList.add('is-active');
      }

      var name = document.createElement('span');
      name.className = 'corpus-history-row-name';
      name.textContent = corpusMeta.name || corpusMeta.id;
      button.appendChild(name);

      var status = document.createElement('span');
      status.className = 'corpus-history-row-status';
      if (corpusMeta.status === 'FAILED') {
        status.classList.add('corpus-history-row-status--failed');
      }
      status.textContent = corpusMeta.status;
      button.appendChild(status);

      button.addEventListener('click', function () {
        closeCorpusHistoryPopover();
        if (corpusMeta.id === activeCorpusId) {
          return;
        }
        activateCorpus(corpusMeta);
        fetch('/api/corpora/' + corpusMeta.id + '/activate', { method: 'POST' }).catch(function (error) {
          console.warn('Failed to record corpus activation', error);
        });
      });

      row.appendChild(button);
      corpusHistoryList.appendChild(row);
    });
  }

  function openCorpusHistoryPopover() {
    if (!corpusHistoryPopover) {
      return;
    }
    corpusHistoryPopover.hidden = false;
    if (corpusHistoryToggle) {
      corpusHistoryToggle.setAttribute('aria-expanded', 'true');
    }
    fetch('/api/corpora')
      .then(function (response) { return response.json(); })
      .then(function (body) {
        renderCorpusHistoryRows(body && body.corpora);
      })
      .catch(function () {
        renderCorpusHistoryRows([]);
      });
  }

  if (corpusHistoryToggle) {
    corpusHistoryToggle.addEventListener('click', function () {
      if (corpusHistoryPopover && corpusHistoryPopover.hidden) {
        openCorpusHistoryPopover();
      } else {
        closeCorpusHistoryPopover();
      }
    });
  }

  document.addEventListener('click', function (event) {
    if (!corpusHistoryPopover || corpusHistoryPopover.hidden) {
      return;
    }
    var target = event.target;
    var withinPopover = target && target.closest && target.closest('#corpus-history-popover');
    var onToggle = target && target.closest && target.closest('#corpus-history-toggle');
    if (!withinPopover && !onToggle) {
      closeCorpusHistoryPopover();
    }
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') {
      closeCorpusHistoryPopover();
    }
  });

  // Story 12.7: page-load bootstrap — auto-restore whichever corpus was
  // most recently activated. `GET /api/corpora` is already ordered
  // most-recently-activated-first (Neo4jCorpusRegistry.list()), so the
  // first entry (if any) is it. A fresh app with no corpora yet leaves
  // #canvas-idle exactly as it is today — nothing else runs.
  fetch('/api/corpora')
    .then(function (response) { return response.json(); })
    .then(function (body) {
      var corpora = (body && body.corpora) || [];
      if (corpora.length === 0) {
        return;
      }
      if (corpusHistoryToggle) {
        corpusHistoryToggle.hidden = false;
      }
      var mostRecent = corpora[0];
      activateCorpus(mostRecent);
      // Opening the app counts as activating the restored corpus too.
      fetch('/api/corpora/' + mostRecent.id + '/activate', { method: 'POST' }).catch(function (error) {
        console.warn('Failed to record corpus activation', error);
      });
    })
    .catch(function () {
      // Auto-restore is a nicety — #canvas-idle's own upload controls
      // remain fully usable if this fails.
    });
})();
