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
  var tabCompare = document.getElementById('tab-compare');
  var comparePanel = document.getElementById('compare-panel');
  var compareQuestion = document.getElementById('compare-question');
  var compareGrid = document.getElementById('compare-grid');
  var compareOverlap = document.getElementById('compare-overlap');
  var compareVerdictLabel = document.getElementById('compare-verdict-label');
  var compareVerdictText = document.getElementById('compare-verdict-text');
  var compareReplayRow = document.getElementById('compare-replay-row');
  var compareRunAgain = document.getElementById('compare-run-again');
  var activeProgressSource = null;
  var activeCorpusId = null;
  var activeCorpusReady = false;
  var activeCorpusOffline = false;
  var currentSearchMode = 'LOCAL';

  // help.js (the contextual help pane) reads no state from this closure; it
  // follows these two document events instead.
  function announceCorpus() {
    document.dispatchEvent(new CustomEvent('graphrag:corpus', {
      detail: { corpusId: activeCorpusId, offline: activeCorpusOffline }
    }));
  }

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
  var entityDetailEyebrow = document.getElementById('entity-detail-eyebrow');
  var entityDetailDescriptionSection = document.getElementById('entity-detail-description-section');
  var entityDetailDescription = document.getElementById('entity-detail-description');
  var entityDetailSourcesSection = document.getElementById('entity-detail-sources-section');
  var entityDetailSources = document.getElementById('entity-detail-sources');
  var entityDetailRelationshipsHeading = document.getElementById('entity-detail-relationships-heading');
  var entityDetailTagsSection = document.getElementById('entity-detail-tags-section');
  var entityDetailRelationshipsSection = document.getElementById('entity-detail-relationships-section');
  var entityDetailCommunitiesSection = document.getElementById('entity-detail-communities-section');
  var entityDetailCommunities = document.getElementById('entity-detail-communities');
  // True while the panel shows the legend's "All communities" list.
  var communityListOpen = false;
  var selectedCommunityId = null;
  var selectedEntityIdentity = null;
  // Story 10.3: the currently-open Entity's `type`, kept alongside
  // `selectedEntityIdentity` so a live toggle change can re-run
  // `renderEntityDetailTags` for the already-open panel without needing to
  // re-look-up the Entity's data.
  var selectedEntityType = null;
  var activeRelationships = [];
  var activeEntityDetails = {};
  // Story 15.4: one passage cache shared by the entity detail panel, the
  // chat's citation panels and Replay's passage captions, keyed by corpus
  // and then by text unit id. Each entry holds the in-flight promise and,
  // once resolved, the passage itself so a cached open needs no "Loading…".
  var passageCache = {};

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

  // Compare CTA: one POST /compare runs the graph answer's mode and the
  // Vector Search baseline fresh, side by side. Nothing is appended to the
  // chat; the result lives in the Compare tab, and the button becomes a link
  // back to it. Delegated, so it works for every .compare-cta upload.js
  // appends later (same pattern as replay.js's delegated .replay-cta handler).
  var COMPARE_CTA_LABEL = '↻ Compare with Vector Search';
  var COMPARE_READY_LABEL = 'Comparison ready — open Compare';

  // Only the newest comparison request may render; older responses are dropped.
  var compareRequestSeq = 0;
  // What the Compare view currently shows, so "Run again" can repeat it.
  var shownComparison = null;

  function markCompareCtaReady(cta) {
    if (!cta) {
      return;
    }
    cta.classList.add('compare-cta--ready');
    cta.textContent = COMPARE_READY_LABEL;
    cta.title = 'Opens the Compare tab with this answer’s comparison.';
    cta.setAttribute('aria-label', COMPARE_READY_LABEL);
  }

  // Runs POST /compare for `request` ({question, mode, corpusId, answerMsg,
  // cta}) and, if it is still the newest request and its corpus is still the
  // active one, renders it and opens the Compare tab.
  function runComparison(request, busyButton, idleLabel) {
    var thisRequest = ++compareRequestSeq;
    if (busyButton) {
      busyButton.disabled = true;
      busyButton.setAttribute('aria-busy', 'true');
      busyButton.textContent = 'Comparing…';
    }
    var settledLabel = idleLabel;

    fetch('/api/corpora/' + encodeURIComponent(request.corpusId) + '/compare', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: request.question, mode: request.mode })
    })
      .then(function (response) {
        return response.json().then(function (body) {
          return { ok: response.ok, body: body };
        });
      })
      .then(function (result) {
        if (thisRequest !== compareRequestSeq || request.corpusId !== activeCorpusId) {
          return;
        }
        if (!result.ok || !result.body || !result.body.graph || !result.body.vector) {
          showErrorBanner(result.body && result.body.error ? result.body.error
              : 'The comparison could not be run. Please try again.');
          return;
        }
        if (request.answerMsg) {
          request.answerMsg.graphragComparison = { body: result.body, request: request };
        }
        if (busyButton === request.cta) {
          settledLabel = null;
        }
        markCompareCtaReady(request.cta);
        var vector = result.body.vector;
        revealVectorSpaceTab(vector.noAnswer ? (vector.reason || '') : (vector.answer || ''), false);
        renderCompareView(result.body, request);
        openCompareTab();
      })
      .catch(function () {
        if (thisRequest === compareRequestSeq) {
          showErrorBanner('The comparison could not be run. Please try again.');
        }
      })
      .finally(function () {
        if (!busyButton) {
          return;
        }
        busyButton.disabled = false;
        busyButton.removeAttribute('aria-busy');
        if (settledLabel !== null) {
          // A ready CTA keeps its link label even when a later run failed.
          busyButton.textContent = busyButton.classList.contains('compare-cta--ready')
              ? COMPARE_READY_LABEL : settledLabel;
        }
      });
  }

  // Compare CTA, delegated so it works for every .compare-cta appended later.
  document.addEventListener('click', function (event) {
    var cta = event.target && event.target.closest ? event.target.closest('.compare-cta') : null;
    if (!cta) {
      return;
    }
    var answerMsg = cta.closest('.message.answer');
    if (!answerMsg) {
      return;
    }
    if (answerMsg.graphragComparison) {
      renderCompareView(answerMsg.graphragComparison.body, answerMsg.graphragComparison.request);
      openCompareTab();
      return;
    }
    var question = answerMsg.dataset.question;
    var corpusId = answerMsg.dataset.corpusId;
    if (!question || !corpusId) {
      return;
    }
    runComparison({
      question: question,
      mode: answerMsg.dataset.mode || 'LOCAL',
      corpusId: corpusId,
      answerMsg: answerMsg,
      cta: cta
    }, cta, COMPARE_CTA_LABEL);
  });

  if (compareRunAgain) {
    compareRunAgain.addEventListener('click', function () {
      if (shownComparison) {
        runComparison(shownComparison, compareRunAgain, 'Run again');
      }
    });
  }

  // Reveals the Compare tab and switches to it: every successful comparison
  // and every click on a "Comparison ready" link opens it.
  function openCompareTab() {
    if (tabCompare) {
      tabCompare.removeAttribute('hidden');
    }
    switchCanvasTab('compare');
  }

  function compareModeLabel(mode) {
    if (mode === 'GLOBAL') {
      return 'Global';
    }
    if (mode === 'DRIFT') {
      return 'DRIFT';
    }
    return 'Local';
  }

  function plural(n, word, pluralWord) {
    return n + ' ' + (n === 1 ? word : (pluralWord || word + 's'));
  }

  function keyFigure(value, label) {
    var item = document.createElement('li');
    item.className = 'compare-figure';
    var number = document.createElement('span');
    number.className = 'compare-figure-value';
    number.textContent = value;
    var caption = document.createElement('span');
    caption.className = 'compare-figure-label';
    caption.textContent = label;
    item.appendChild(number);
    item.appendChild(caption);
    return item;
  }

  // One column of the Compare view: the side's answer (with `[n]` markers
  // and a Sources list when cited), an "also used by the other side" badge on
  // overlapping sources, and its key figures. Text goes in via textContent.
  // "Retrieved passages": every passage the side retrieved, cited or not,
  // each opening its full text in the column's own panel.
  function retrievedPassages(side, corpusId, passageKind, emptyText) {
    var section = document.createElement('div');
    section.className = 'compare-retrieved';
    var heading = document.createElement('p');
    heading.className = 'answer-sources-heading';
    heading.textContent = 'Retrieved passages';
    section.appendChild(heading);
    var retrieved = Array.isArray(side.retrieved) ? side.retrieved : [];
    if (!retrieved.length) {
      var empty = document.createElement('p');
      empty.className = 'compare-retrieved-empty';
      empty.textContent = emptyText;
      section.appendChild(empty);
      return section;
    }
    var list = document.createElement('ul');
    list.className = 'answer-sources-list compare-retrieved-list';
    var panel = document.createElement('div');
    panel.className = 'answer-passage compare-retrieved-passage';
    panel.id = 'answer-passage-' + (++answerPassageSeq);
    panel.hidden = true;
    var panelTitle = document.createElement('p');
    panelTitle.className = 'answer-passage-title';
    var panelText = document.createElement('p');
    panelText.className = 'answer-passage-text';
    panelText.setAttribute('aria-live', 'polite');
    panel.appendChild(panelTitle);
    panel.appendChild(panelText);
    var openIndex = null;
    var rows = [];

    retrieved.forEach(function (passage, index) {
      var id = passageKind === 'chunk' ? passage.chunkId : passage.textUnitId;
      if (!passage || !id) {
        return;
      }
      var shared = passageKind === 'chunk' ? passage.sharedWithGraph : passage.sharedWithVector;
      var label = (index + 1) + '. ' + (passage.documentName || 'Unknown document')
          + (passage.excerpt ? ' · ' + passage.excerpt : '');
      var item = document.createElement('li');
      item.className = 'answer-source compare-retrieved-item';
      var row = document.createElement('button');
      row.type = 'button';
      row.className = 'answer-source-toggle compare-retrieved-toggle';
      row.setAttribute('aria-expanded', 'false');
      row.setAttribute('aria-controls', panel.id);
      row.textContent = label;
      row.addEventListener('click', function () {
        if (openIndex === index) {
          openIndex = null;
          panel.hidden = true;
        } else {
          openIndex = index;
          panelTitle.textContent = (index + 1) + '. ' + (passage.documentName || 'Unknown document');
          panel.hidden = false;
          fillPassageText(panelText, corpusId, id, passageKind,
              passageKind === 'chunk' && passage.excerpt ? passage.excerpt : null);
        }
        rows.forEach(function (other, otherIndex) {
          other.setAttribute('aria-expanded', String(openIndex === otherIndex));
        });
      });
      rows[index] = row;
      item.appendChild(row);
      if (shared) {
        item.classList.add('answer-source--shared');
        var badge = document.createElement('span');
        badge.className = 'compare-shared-badge';
        badge.textContent = 'also used by the other side';
        item.appendChild(badge);
      }
      list.appendChild(item);
    });
    section.appendChild(list);
    section.appendChild(panel);
    return section;
  }

  function compareColumn(className, title, side, sharedFlags, corpusId, passageKind) {
    var column = document.createElement('section');
    column.className = 'compare-col ' + className;
    column.setAttribute('aria-label', title);

    var heading = document.createElement('h3');
    heading.className = 'compare-col-title';
    heading.textContent = title;
    column.appendChild(heading);

    var content = document.createElement('p');
    content.className = 'compare-col-answer';
    var citations = side.citations || [];
    if (side.noAnswer) {
      content.classList.add('compare-col-answer--none');
      content.textContent = side.reason || 'No answer was returned.';
      column.appendChild(content);
    } else if (!side.answer || !hasCitations(citations)) {
      content.textContent = side.answer || 'No answer was returned.';
      column.appendChild(content);
    } else {
      var parts = buildCitationSources(column, content, side.answer, citations, corpusId,
          { kind: passageKind, shared: sharedFlags });
      column.appendChild(content);
      column.appendChild(parts.sources);
      column.appendChild(parts.panel);
    }

    column.appendChild(retrievedPassages(side, corpusId, passageKind, passageKind === 'chunk'
        ? 'No passages retrieved.'
        : 'No source passages read (offline keyword matching).'));

    var stats = side.stats || {};
    var figures = document.createElement('ul');
    figures.className = 'compare-figures';
    figures.setAttribute('aria-label', title + ' key figures');
    figures.appendChild(keyFigure(String(stats.contextItems || 0),
        (stats.contextItems === 1 ? 'context item' : 'context items')));
    figures.appendChild(keyFigure(String(stats.distinctDocuments || 0),
        (stats.distinctDocuments === 1 ? 'document' : 'documents')));
    figures.appendChild(keyFigure(String(citations.length), citations.length === 1 ? 'citation' : 'citations'));
    figures.appendChild(keyFigure((stats.latencyMs || 0) + ' ms', 'latency'));
    column.appendChild(figures);
    return column;
  }

  function compareReplayButton(label, side, corpusId, projection) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'replay-cta compare-replay';
    button.dataset.traceId = side.traceId || '';
    button.dataset.stepCount = String(side.traceStepCount || 0);
    button.dataset.corpusId = corpusId || '';
    // The trace pane names the mode and groups the steps into its phases.
    button.dataset.mode = side.mode || (label === 'Replay Vector' ? 'VECTOR' : '');
    if (projection) {
      button.dataset.queryProjection = JSON.stringify(projection);
    }
    button.disabled = !side.traceId;
    button.textContent = label + ' — ' + plural(side.traceStepCount || 0, 'step');
    return button;
  }

  // Fills the Compare tab from one POST /compare response for `request`.
  function renderCompareView(comparison, request) {
    if (!comparePanel || !comparison) {
      return;
    }
    shownComparison = request || null;
    var corpusId = request ? request.corpusId : activeCorpusId;
    var graph = comparison.graph || {};
    // A vector citation names its chunk as `chunkId`; the citation renderer
    // reads `textUnitId`, so map it over (passages then load as chunks).
    var vector = Object.assign({}, comparison.vector || {});
    vector.citations = (vector.citations || []).map(function (citation) {
      return citation ? {
        textUnitId: citation.chunkId || '',
        documentName: citation.documentName || '',
        excerpt: citation.excerpt || ''
      } : null;
    });
    var overlap = comparison.overlap || {};
    var graphShared = (overlap.graph || []).map(function (entry) { return !!(entry && entry.sharedWithVector); });
    var vectorShared = (overlap.vector || []).map(function (entry) { return !!(entry && entry.sharedWithGraph); });
    var graphTitle = 'GraphRAG · ' + compareModeLabel(graph.mode);

    if (compareQuestion) {
      compareQuestion.textContent = comparison.question || '';
    }
    if (compareGrid) {
      compareGrid.textContent = '';
      compareGrid.appendChild(compareColumn('compare-col--graph', graphTitle, graph, graphShared, corpusId,
          'text-unit'));
      compareGrid.appendChild(compareColumn('compare-col--vector', 'Vector Search', vector, vectorShared, corpusId,
          'chunk'));
    }
    if (compareOverlap) {
      var shared = overlap.sharedPassages || 0;
      var total = overlap.vectorPassages || 0;
      compareOverlap.textContent = total
          ? shared + ' of the ' + plural(total, 'passage') + ' Vector Search retrieved '
              + (shared === 1 ? 'was' : 'were') + ' also read by GraphRAG.'
          : 'Vector Search retrieved no passages.';
    }
    var verdict = comparison.verdict || {};
    if (compareVerdictLabel) {
      compareVerdictLabel.textContent = verdict.source === 'llm' ? 'LLM verdict' : 'Rule-based summary';
      compareVerdictLabel.dataset.source = verdict.source === 'llm' ? 'llm' : 'rule';
    }
    if (compareVerdictText) {
      compareVerdictText.textContent = verdict.text || '';
    }
    if (compareReplayRow) {
      compareReplayRow.textContent = '';
      compareReplayRow.appendChild(compareReplayButton('Replay GraphRAG', graph, corpusId, null));
      compareReplayRow.appendChild(compareReplayButton('Replay Vector', vector, corpusId,
          Array.isArray(vector.queryProjection) ? vector.queryProjection : null));
    }
  }

  function resetCompareView() {
    shownComparison = null;
    compareRequestSeq++;
    if (tabCompare) {
      tabCompare.setAttribute('hidden', '');
    }
    if (comparePanel) {
      comparePanel.hidden = true;
    }
    [compareQuestion, compareGrid, compareOverlap, compareVerdictLabel, compareVerdictText, compareReplayRow]
      .forEach(function (el) {
        if (el) {
          el.textContent = '';
        }
      });
  }

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
  // `autoSwitch === false` (a comparison, which opens the Compare tab
  // instead) never switches to it.
  function revealVectorSpaceTab(answerText, autoSwitch) {
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
    if (wasHidden && autoSwitch !== false) {
      switchCanvasTab('vector-space');
    }
  }

  // Story 8-3: tab switching between 'knowledge-graph', 'vector-space' and
  // 'compare'. The Knowledge Graph surface is hidden on both other tabs.
  function switchCanvasTab(which) {
    var target = which === 'vector-space' || which === 'compare' ? which : 'knowledge-graph';
    var showGraph = target === 'knowledge-graph';
    [[tabKnowledgeGraph, 'knowledge-graph'], [tabVectorSpace, 'vector-space'], [tabCompare, 'compare']]
      .forEach(function (pair) {
        if (pair[0]) {
          pair[0].setAttribute('aria-selected', pair[1] === target ? 'true' : 'false');
          pair[0].tabIndex = pair[1] === target ? 0 : -1;
        }
      });
    // graph-canvas, drift-pane, replay-scrubber, entity-detail-panel —
    // all part of the Knowledge Graph tab surface.
    var kgEls = [
      document.getElementById('graph-canvas'),
      document.getElementById('drift-pane'),
      document.getElementById('graph-legend'),
      document.getElementById('graph-eyebrow'),
      document.getElementById('canvas-settings-toggle'),
      document.getElementById('canvas-zoom-controls'),
      document.getElementById('replay-scrubber'),
      document.getElementById('entity-detail-panel'),
      document.getElementById('entity-search')
    ];
    if (!showGraph) {
      // The settings button hides below (part of kgEls) — always close its
      // popover too, so it never lingers open-but-invisible behind another
      // tab and reappears already-open when switching back.
      closeCanvasSettingsPopover();
    }
    kgEls.forEach(function (el) {
      if (!el) {
        return;
      }
      // Only toggle elements that are already visible (not ones that are
      // hidden for their own reasons, e.g. canvas-idle or workflow-status).
      if (!showGraph) {
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
      vectorSpacePanel.hidden = target !== 'vector-space';
    }
    if (comparePanel) {
      comparePanel.hidden = target !== 'compare';
    }
    // The Retrieval Trace pane (trace-pane.js) follows the tab its trace
    // belongs to: a graph trace shows on the Knowledge Graph tab, a vector
    // trace on the Vector Space tab, neither on Compare.
    document.dispatchEvent(new CustomEvent('graphrag:canvas-tab', { detail: { tab: target } }));
  }

  // The visible tabs in order, each with the view it switches to.
  function visibleCanvasTabs() {
    return [[tabKnowledgeGraph, 'knowledge-graph'], [tabVectorSpace, 'vector-space'], [tabCompare, 'compare']]
      .filter(function (pair) { return pair[0] && !pair[0].hidden; });
  }

  // ARIA tabs pattern: Arrow keys move to the previous/next visible tab
  // (wrapping), Home/End to the first/last; focus follows and activates.
  function onCanvasTabKeydown(event) {
    var tabs = visibleCanvasTabs();
    var index = -1;
    tabs.forEach(function (pair, i) {
      if (pair[0] === event.currentTarget) {
        index = i;
      }
    });
    if (index < 0 || tabs.length < 2) {
      return;
    }
    var next;
    if (event.key === 'ArrowRight') {
      next = (index + 1) % tabs.length;
    } else if (event.key === 'ArrowLeft') {
      next = (index - 1 + tabs.length) % tabs.length;
    } else if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = tabs.length - 1;
    } else {
      return;
    }
    event.preventDefault();
    tabs[next][0].focus();
    switchCanvasTab(tabs[next][1]);
  }

  [[tabKnowledgeGraph, 'knowledge-graph'], [tabVectorSpace, 'vector-space'], [tabCompare, 'compare']]
    .forEach(function (pair) {
      if (!pair[0]) {
        return;
      }
      pair[0].addEventListener('click', function () {
        switchCanvasTab(pair[1]);
      });
      pair[0].addEventListener('keydown', onCanvasTabKeydown);
    });


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
    selectedCommunityId = null;
    communityListOpen = false;
    if (!entityDetailPanel) {
      return;
    }
    entityDetailPanel.classList.remove('is-open');
    entityDetailPanel.setAttribute('aria-hidden', 'true');
  }

  function resetActiveEntityDetails() {
    activeEntityDetails = {};
    passageCache = {};
  }

  // Vector chunks share the passage cache under a "chunk:" key, so a chunk
  // id can never collide with a text unit id.
  function passageKey(textUnitId, kind) {
    return kind === 'chunk' ? 'chunk:' + textUnitId : textUnitId;
  }

  function passageEntry(corpusId, textUnitId, kind) {
    var byCorpus = passageCache[corpusId];
    var key = passageKey(textUnitId, kind);
    return byCorpus && Object.prototype.hasOwnProperty.call(byCorpus, key) ? byCorpus[key] : null;
  }

  // The passage for `textUnitId` in `corpusId`, if it has already been
  // fetched: `{documentName, ordinal, text}`, else null.
  function cachedPassage(corpusId, textUnitId, kind) {
    var entry = passageEntry(corpusId, textUnitId, kind);
    return entry && entry.passage ? entry.passage : null;
  }

  // Fetches one passage from Story 13.4's text-unit endpoint (or, for
  // `kind === 'chunk'`, one vector chunk from the chunk endpoint), once per
  // corpus and id. Resolves to `{documentName, ordinal, text}`; rejects when
  // the passage is not available (a failed fetch is not cached, so a later
  // open retries it).
  function loadPassage(corpusId, textUnitId, kind) {
    if (!corpusId || !textUnitId) {
      return Promise.reject(new Error('Passage not available'));
    }
    var existing = passageEntry(corpusId, textUnitId, kind);
    if (existing) {
      return existing.promise;
    }
    var key = passageKey(textUnitId, kind);
    var byCorpus = passageCache[corpusId] || (passageCache[corpusId] = {});
    var entry = {};
    entry.promise = fetch('/api/corpora/' + encodeURIComponent(corpusId)
        + (kind === 'chunk' ? '/chunks/' : '/text-units/') + encodeURIComponent(textUnitId))
      .then(function (response) {
        if (!response.ok) {
          throw new Error('Passage not available');
        }
        return response.json();
      })
      .then(function (body) {
        var rawOrdinal = body ? body.ordinal : null;
        var ordinal = rawOrdinal !== null && rawOrdinal !== undefined && rawOrdinal !== ''
            && Number.isFinite(Number(rawOrdinal)) ? Number(rawOrdinal) : null;
        entry.passage = {
          documentName: (body && body.documentName) || '',
          ordinal: ordinal,
          text: (body && body.text) || ''
        };
        return entry.passage;
      })
      .catch(function (error) {
        if (passageCache[corpusId] === byCorpus && byCorpus[key] === entry) {
          delete byCorpus[key];
        }
        throw error;
      });
    byCorpus[key] = entry;
    return entry.promise;
  }

  // replay.js reads passages (for its TEXT_UNIT captions) through the same
  // cache rather than fetching on its own.
  window.Passages = {
    load: loadPassage,
    cached: cachedPassage
  };

  function normalizeSources(sources) {
    return Array.isArray(sources) ? sources.filter(function (source) {
      return source && source.textUnitId;
    }).map(function (source) {
      return {
        textUnitId: source.textUnitId,
        documentName: source.documentName || '',
        ordinal: Number.isFinite(Number(source.ordinal)) ? Number(source.ordinal) : 0
      };
    }) : [];
  }

  // Story 15.4: the text unit ids an Entity cites, handed to the canvas so
  // Replay can light up the Entities citing a passage step. Undefined when
  // the payload carries no `sources` at all, so the canvas keeps what it has.
  function sourceTextUnitIds(entity) {
    if (!entity || !Array.isArray(entity.sources)) {
      return undefined;
    }
    return normalizeSources(entity.sources).map(function (source) { return source.textUnitId; });
  }

  function updateActiveEntityDetails(entity) {
    if (!entity || !entity.identity) {
      return;
    }
    activeEntityDetails[entity.identity] = {
      description: entity.description || '',
      sources: normalizeSources(entity.sources)
    };
    if (selectedEntityIdentity === entity.identity) {
      renderEntityDetailDescription(entity.identity);
      renderEntityDetailSources(entity.identity);
    }
  }

  function moveActiveEntityDetails(previousIdentity, entity) {
    if (previousIdentity && activeEntityDetails[previousIdentity]) {
      delete activeEntityDetails[previousIdentity];
    }
    updateActiveEntityDetails(entity);
  }

  function renderEntityDetailDescription(identity) {
    var details = activeEntityDetails[identity] || {};
    var description = (details.description || '').trim();
    if (entityDetailDescription) {
      entityDetailDescription.textContent = description;
    }
    if (entityDetailDescriptionSection) {
      entityDetailDescriptionSection.hidden = !description;
    }
  }

  function renderEntityDetailSources(identity) {
    if (!entityDetailSources || !entityDetailSourcesSection) {
      return;
    }
    var details = activeEntityDetails[identity] || {};
    var sources = details.sources || [];
    entityDetailSources.textContent = '';
    entityDetailSourcesSection.hidden = sources.length === 0 || !identity;
    if (entityDetailSourcesSection.hidden) {
      return;
    }
    var corpusId = activeCorpusId;
    sources.forEach(function (source) {
      var item = document.createElement('li');
      item.className = 'node-detail-source';

      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'node-detail-source-toggle';
      button.setAttribute('aria-expanded', 'false');
      button.textContent = (source.documentName || 'Unknown document') + ' · passage ' + (source.ordinal + 1);

      var passage = document.createElement('p');
      passage.className = 'node-detail-source-text';
      passage.hidden = true;

      button.addEventListener('click', function () {
        var expanded = button.getAttribute('aria-expanded') === 'true';
        button.setAttribute('aria-expanded', expanded ? 'false' : 'true');
        passage.hidden = expanded;
        if (expanded) {
          return;
        }
        fillPassageText(passage, corpusId, source.textUnitId);
      });

      item.appendChild(button);
      item.appendChild(passage);
      entityDetailSources.appendChild(item);
    });
  }

  // Writes a passage's full text into `element` (textContent only), from the
  // shared cache when it is there, else `placeholder` (default "Loading…")
  // until the fetch settles. `kind === 'chunk'` reads a vector chunk.
  function fillPassageText(element, corpusId, textUnitId, kind, placeholder) {
    element.dataset.textUnitId = textUnitId;
    var cached = cachedPassage(corpusId, textUnitId, kind);
    if (cached) {
      element.textContent = cached.text;
      return;
    }
    element.textContent = placeholder || 'Loading…';
    loadPassage(corpusId, textUnitId, kind)
      .then(function (passage) {
        if (element.dataset.textUnitId === textUnitId) {
          element.textContent = passage.text;
        }
      })
      .catch(function () {
        if (element.dataset.textUnitId === textUnitId) {
          element.textContent = 'Passage not available';
        }
      });
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
      lines.push({
        text: isSource
            ? '→ ' + relationshipType + ' → ' + otherName
            : '← ' + relationshipType + ' ← ' + otherName,
        description: relationship.description || ''
      });
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
      item.textContent = line.text;
      if ((line.description || '').trim()) {
        item.title = line.description;
      }
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

  // Modes: 'entity', 'community' (one Community's detail) and 'list' (the
  // legend's "All communities" list).
  function setDetailMode(mode) {
    var isCommunity = mode === 'community';
    var isList = mode === 'list';
    communityListOpen = isList;
    if (entityDetailEyebrow) {
      entityDetailEyebrow.textContent = isList ? 'Communities' : (isCommunity ? 'Community' : 'Entity');
    }
    if (entityDetailRelationshipsHeading) {
      entityDetailRelationshipsHeading.textContent = isCommunity ? 'Members' : 'Relationships';
    }
    if (entityDetailDescriptionSection) {
      entityDetailDescriptionSection.hidden = !isCommunity;
    }
    if (entityDetailSourcesSection) {
      entityDetailSourcesSection.hidden = true;
    }
    if (entityDetailTagsSection) {
      entityDetailTagsSection.hidden = isCommunity || isList;
    }
    if (entityDetailRelationshipsSection) {
      entityDetailRelationshipsSection.hidden = isList;
    }
    if (entityDetailCommunitiesSection) {
      entityDetailCommunitiesSection.hidden = !isList;
    }
  }

  function entityCountLabel(count) {
    return count + (count === 1 ? ' entity' : ' entities');
  }

  // The legend's "All communities" button: every Community, largest first
  // (GraphCanvas.listCommunities' order). A row opens that Community exactly
  // as tapping its hull does (GraphCanvas.activateCommunity, which fires the
  // `onCommunityTap` callback below).
  function openCommunityListPanel(communities) {
    if (!entityDetailPanel) {
      return;
    }
    var list = communities || [];
    selectedCommunityId = null;
    selectedEntityIdentity = null;
    selectedEntityType = null;
    setDetailMode('list');
    if (entityDetailName) {
      entityDetailName.textContent = list.length + (list.length === 1 ? ' community' : ' communities');
    }
    if (entityDetailType) {
      entityDetailType.textContent = 'Largest first. Pick one to focus it on the graph.';
    }
    if (entityDetailCommunities) {
      entityDetailCommunities.textContent = '';
      list.forEach(function (community) {
        var item = document.createElement('li');
        var row = document.createElement('button');
        row.type = 'button';
        row.className = 'node-detail-community-row';
        row.dataset.communityId = community.communityId;
        row.title = community.summary || community.name;

        var swatch = document.createElement('span');
        swatch.className = 'graph-legend-swatch';
        swatch.style.background = community.fill || '';
        swatch.style.borderColor = community.labelColor || '';
        swatch.setAttribute('aria-hidden', 'true');
        row.appendChild(swatch);

        var name = document.createElement('span');
        name.className = 'node-detail-community-name';
        name.textContent = community.name || community.communityId;
        row.appendChild(name);

        var count = document.createElement('span');
        count.className = 'node-detail-community-count';
        count.textContent = entityCountLabel(community.memberCount || 0);
        row.appendChild(count);

        row.addEventListener('click', function () {
          if (window.GraphCanvas && typeof window.GraphCanvas.activateCommunity === 'function') {
            window.GraphCanvas.activateCommunity(community.communityId);
          }
        });
        item.appendChild(row);
        entityDetailCommunities.appendChild(item);
      });
    }
    entityDetailPanel.scrollTop = 0;
    entityDetailPanel.classList.add('is-open');
    entityDetailPanel.setAttribute('aria-hidden', 'false');
  }

  function openCommunityDetailPanel(community) {
    if (!entityDetailPanel) {
      return;
    }
    selectedCommunityId = community.communityId;
    selectedEntityIdentity = null;
    selectedEntityType = null;
    setDetailMode('community');
    var members = community.members || [];
    if (entityDetailName) {
      entityDetailName.textContent = community.name || community.communityId;
    }
    if (entityDetailType) {
      entityDetailType.textContent = entityCountLabel(members.length);
    }
    if (entityDetailDescription) {
      entityDetailDescription.textContent = community.summary || 'No description available.';
    }
    if (entityDetailRelationships) {
      entityDetailRelationships.textContent = '';
      members.forEach(function (member) {
        var item = document.createElement('li');
        item.textContent = member.name + (member.type ? ' (' + member.type + ')' : '');
        entityDetailRelationships.appendChild(item);
      });
    }
    entityDetailPanel.classList.add('is-open');
    entityDetailPanel.setAttribute('aria-hidden', 'false');
  }

  function openEntityDetailPanel(nodeData) {
    if (!entityDetailPanel) {
      return;
    }
    selectedEntityIdentity = nodeData.identity;
    selectedEntityType = nodeData.type;
    selectedCommunityId = null;
    setDetailMode('entity');
    if (entityDetailName) {
      entityDetailName.textContent = nodeData.name || nodeData.identity;
    }
    if (entityDetailType) {
      entityDetailType.textContent = 'Type: ' + (nodeData.type || 'Unknown');
    }
    renderEntityDetailDescription(nodeData.identity);
    renderEntityDetailSources(nodeData.identity);
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

  if (window.GraphCanvas && typeof window.GraphCanvas.onCommunityTap === 'function') {
    window.GraphCanvas.onCommunityTap(function (community) {
      if (selectedCommunityId === community.communityId) {
        closeEntityDetailPanel();
        return;
      }
      openCommunityDetailPanel(community);
    });
  }

  if (window.GraphCanvas && typeof window.GraphCanvas.onAllCommunities === 'function') {
    window.GraphCanvas.onAllCommunities(function (communities) {
      if (communityListOpen) {
        closeEntityDetailPanel();
        return;
      }
      openCommunityListPanel(communities);
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
                question,
                undefined,
                !!result.body.noAnswer,
                result.body.citations);
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
    resetActiveEntityDetails();

    activeCorpusId = null;
    activeCorpusReady = false;
    activeCorpusOffline = false;
    announceCorpus();

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
    resetCompareView();

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
    resetActiveEntityDetails();

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
    announceCorpus();
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

  // Story 15.4: `[1]` / `[1, 2]` markers in a cited answer. Each number in a
  // group becomes its own button; brackets and separators stay plain text so
  // the span's textContent still reads exactly as the answer text.
  var answerPassageSeq = 0;
  var CITATION_MARKER = /\[(\d+(?:\s*,\s*\d+)*)\]/g;

  // `[i]` refers to `citations[i-1]`, so the array keeps its positions; an
  // entry without a text unit id simply never matches a marker.
  function citationAt(citations, n) {
    var citation = n >= 1 && n <= citations.length ? citations[n - 1] : null;
    return citation && citation.textUnitId ? citation : null;
  }

  function hasCitations(citations) {
    return Array.isArray(citations) && citations.some(function (citation) {
      return citation && citation.textUnitId;
    });
  }

  // Fills `content` with the answer text, turning every `[i]` that has a
  // matching `citations[i-1]` into a marker button. Text goes in through
  // text nodes only, never as markup.
  function renderCitedAnswerText(content, answerText, citations, openCitation, panelId) {
    var cursor = 0;
    var match;
    CITATION_MARKER.lastIndex = 0;
    while ((match = CITATION_MARKER.exec(answerText)) !== null) {
      content.appendChild(document.createTextNode(answerText.slice(cursor, match.index) + '['));
      var group = match[1];
      var numberPattern = /\d+/g;
      var groupCursor = 0;
      var number;
      while ((number = numberPattern.exec(group)) !== null) {
        if (number.index > groupCursor) {
          content.appendChild(document.createTextNode(group.slice(groupCursor, number.index)));
        }
        var n = parseInt(number[0], 10);
        var citation = citationAt(citations, n);
        if (citation) {
          var marker = document.createElement('button');
          marker.type = 'button';
          marker.className = 'citation-marker';
          marker.dataset.citation = String(n);
          marker.setAttribute('aria-expanded', 'false');
          marker.setAttribute('aria-controls', panelId);
          marker.setAttribute('aria-label', 'Source ' + n + ': ' + (citation.documentName || 'Unknown document'));
          marker.textContent = number[0];
          marker.addEventListener('click', openCitation.bind(null, n));
          content.appendChild(marker);
        } else {
          content.appendChild(document.createTextNode(number[0]));
        }
        groupCursor = number.index + number[0].length;
      }
      content.appendChild(document.createTextNode(group.slice(groupCursor) + ']'));
      cursor = match.index + match[0].length;
    }
    content.appendChild(document.createTextNode(answerText.slice(cursor)));
  }

  // The "Sources" list under a cited answer plus its one inline passage
  // panel. Activating a marker or a row toggles that citation's full passage
  // in the panel (fetched once per text unit through the shared cache).
  // `options.kind === 'chunk'` reads vector chunks (shown from the citation's
  // excerpt until the full chunk arrives); `options.shared[i]` badges source
  // `i + 1` as also used by the other side of a comparison.
  function buildCitationSources(message, content, answerText, citations, corpusId, options) {
    var openNumber = null;
    var kind = options && options.kind === 'chunk' ? 'chunk' : 'text-unit';
    var shared = options && Array.isArray(options.shared) ? options.shared : [];

    var sources = document.createElement('div');
    sources.className = 'answer-sources';
    var heading = document.createElement('p');
    heading.className = 'answer-sources-heading';
    heading.textContent = 'Sources';
    sources.appendChild(heading);
    var list = document.createElement('ul');
    list.className = 'answer-sources-list';
    sources.appendChild(list);

    var panel = document.createElement('div');
    panel.className = 'answer-passage';
    panel.id = 'answer-passage-' + (++answerPassageSeq);
    panel.hidden = true;
    var panelTitle = document.createElement('p');
    panelTitle.className = 'answer-passage-title';
    var panelText = document.createElement('p');
    panelText.className = 'answer-passage-text';
    panelText.setAttribute('aria-live', 'polite');
    panel.appendChild(panelTitle);
    panel.appendChild(panelText);

    function syncExpanded() {
      Array.prototype.forEach.call(
          message.querySelectorAll('.citation-marker, .answer-source-toggle'), function (trigger) {
            trigger.setAttribute('aria-expanded',
                String(openNumber !== null && trigger.dataset.citation === String(openNumber)));
          });
    }

    function openCitation(n) {
      if (openNumber === n) {
        openNumber = null;
        panel.hidden = true;
        syncExpanded();
        return;
      }
      openNumber = n;
      var citation = citations[n - 1];
      panelTitle.textContent = n + '. ' + (citation.documentName || 'Unknown document');
      panel.hidden = false;
      fillPassageText(panelText, corpusId, citation.textUnitId, kind,
          kind === 'chunk' && citation.excerpt ? citation.excerpt : null);
      syncExpanded();
    }

    renderCitedAnswerText(content, answerText, citations, openCitation, panel.id);

    citations.forEach(function (citation, index) {
      var n = index + 1;
      if (!citationAt(citations, n)) {
        return;
      }
      var item = document.createElement('li');
      item.className = 'answer-source';
      var row = document.createElement('button');
      row.type = 'button';
      row.className = 'answer-source-toggle';
      row.dataset.citation = String(n);
      row.setAttribute('aria-expanded', 'false');
      row.setAttribute('aria-controls', panel.id);
      row.textContent = n + '. ' + (citation.documentName || 'Unknown document')
          + (citation.excerpt ? ' · ' + citation.excerpt : '');
      row.addEventListener('click', function () { openCitation(n); });
      item.appendChild(row);
      if (shared[index]) {
        item.classList.add('answer-source--shared');
        var badge = document.createElement('span');
        badge.className = 'compare-shared-badge';
        badge.textContent = 'also used by the other side';
        item.appendChild(badge);
      }
      list.appendChild(item);
    });

    return { sources: sources, panel: panel };
  }

  function appendAnswer(text, mode, traceId, traceStepCount, question, queryProjection, noAnswer, citations) {
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
    if (noAnswer || !text || !hasCitations(citations)) {
      content.textContent = answerText;
      message.appendChild(content);
    } else {
      var citationParts = buildCitationSources(message, content, answerText, citations, activeCorpusId);
      message.appendChild(content);
      message.appendChild(citationParts.sources);
      message.appendChild(citationParts.panel);
    }

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
      var compareCtaExplanation = 'Re-runs this question through this mode and a plain vector-similarity ' +
          'search (no knowledge graph), and opens the Compare tab with both cited answers side by side, ' +
          'their sources, key figures and a short verdict. The Vector Space tab shows where the ' +
          'corpus\'s chunks sit in embedding space.';
      compareCta.title = compareCtaExplanation;
      compareCta.setAttribute('aria-label', compareCtaExplanation);
      compareCta.textContent = COMPARE_CTA_LABEL;
      var compareRow = document.createElement('div');
      compareRow.className = 'compare-row';
      compareRow.appendChild(compareCta);
      var compareHelp = document.createElement('button');
      compareHelp.type = 'button';
      compareHelp.className = 'help-btn';
      compareHelp.dataset.help = 'vector-vs-graphrag';
      compareHelp.setAttribute('aria-label', 'Help: vector search versus GraphRAG');
      compareHelp.textContent = '?';
      compareRow.appendChild(compareHelp);
      message.appendChild(compareRow);
    }

    // Help pane hooks: a "?" beside the answer (reading-an-answer) and, on
    // the Compare button, the vector-vs-graphrag topic.
    var answerHelp = document.createElement('button');
    answerHelp.type = 'button';
    answerHelp.className = 'help-btn help-btn--answer';
    answerHelp.dataset.help = 'reading-an-answer';
    answerHelp.setAttribute('aria-label', 'Help: reading an answer');
    answerHelp.textContent = '?';
    message.appendChild(answerHelp);

    chatThread.appendChild(message);
    chatThread.scrollTop = chatThread.scrollHeight;

    document.dispatchEvent(new CustomEvent('graphrag:answer', {
      detail: {
        mode: activeMode,
        traceId: traceId || null,
        stepCount: traceStepCount || 0,
        question: question || null,
        corpusId: activeCorpusId,
        answer: answerText,
        noAnswer: !!noAnswer
      }
    }));
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

    // Story 13.1: extraction runs one Text Unit (passage) at a time; each
    // unit announces itself before its own entity/relationship events.
    activeProgressSource.addEventListener('text-unit-extracted', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        if (data && workflowState === 'BUILDING' && workflowStatusText) {
          workflowStatusText.textContent = 'Extracting passage ' + data.index + ' of ' + data.total
            + ' — ' + data.documentName;
        }
      } catch (e) {
        console.warn('Invalid SSE text-unit payload', e);
      }
    });

    activeProgressSource.addEventListener('entity-extracted', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        if (data && window.GraphCanvas) {
          updateActiveEntityDetails(data);
          window.GraphCanvas.addEntity(data.identity, data.name, data.type, sourceTextUnitIds(data));
        }
      } catch (e) {
        console.warn('Invalid SSE entity-extracted payload', e);
      }
    });

    activeProgressSource.addEventListener('entity-retyped', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        if (data && window.GraphCanvas) {
          moveActiveEntityDetails(data.previousIdentity, data);
          window.GraphCanvas.retypeEntity(data.previousIdentity, data.identity, data.name, data.type,
              sourceTextUnitIds(data));
          activeRelationships.forEach(function (relationship) {
            if (relationship.sourceIdentity === data.previousIdentity) {
              relationship.sourceIdentity = data.identity;
              relationship.source = data.name;
            }
            if (relationship.targetIdentity === data.previousIdentity) {
              relationship.targetIdentity = data.identity;
              relationship.target = data.name;
            }
          });
          if (selectedEntityIdentity === data.previousIdentity) {
            selectedEntityIdentity = data.identity;
            selectedEntityType = data.type;
            if (entityDetailName) {
              entityDetailName.textContent = data.name || data.identity;
            }
            if (entityDetailType) {
              entityDetailType.textContent = 'Type: ' + (data.type || 'Unknown');
            }
            renderEntityDetailDescription(data.identity);
            renderEntityDetailSources(data.identity);
            renderEntityDetailRelationships(data.identity);
            renderEntityDetailTags(data.type);
          }
        }
      } catch (e) {
        console.warn('Invalid SSE entity-retyped payload', e);
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
          // read a node's relationships from. Overlapping passages (Story
          // 13.1) can re-emit the same Relationship, so keep one per edge.
          var duplicate = null;
          activeRelationships.some(function (existing) {
            var matches = existing.sourceIdentity === data.sourceIdentity
                && existing.targetIdentity === data.targetIdentity
                && (existing.type || 'related_to') === (data.type || 'related_to');
            if (matches) {
              duplicate = existing;
            }
            return matches;
          });
          if (duplicate) {
            duplicate.description = data.description || '';
          } else {
            activeRelationships.push(data);
          }
          if (selectedEntityIdentity === data.sourceIdentity || selectedEntityIdentity === data.targetIdentity) {
            renderEntityDetailRelationships(selectedEntityIdentity);
          }
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
          window.GraphCanvas.addCommunity(data.communityId, data.summary, data.memberEntityIdentities,
              data.title);
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

  // The last state passed to renderWorkflowStatus, so live progress events
  // (Story 13.1) only rewrite the status line while the graph is building.
  // Declared without an initializer so a call made before this line runs
  // (hoisting) is never reset.
  var workflowState;

  function renderWorkflowStatus(state, failureMessage) {
    workflowState = state;
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
    resetActiveEntityDetails();

    activeCorpusId = corpusMeta.id;
    activeCorpusReady = false;
    activeCorpusOffline = false;
    announceCorpus();
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
    resetCompareView();

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
              updateActiveEntityDetails(entity);
              window.GraphCanvas.addEntity(entity.identity, entity.name, entity.type, sourceTextUnitIds(entity));
            });
            (body.relationships || []).forEach(function (relationship) {
              window.GraphCanvas.addRelationship(
                  relationship.sourceIdentity, relationship.source,
                  relationship.targetIdentity, relationship.target, relationship.type);
              activeRelationships.push(relationship);
            });
            (body.communities || []).forEach(function (community) {
              window.GraphCanvas.addCommunity(
                  community.communityId, community.summary, community.memberEntityIdentities,
                  community.title);
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
