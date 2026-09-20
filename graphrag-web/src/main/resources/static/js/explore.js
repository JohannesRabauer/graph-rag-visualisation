(function () {
  'use strict';

  var loadingState = document.getElementById('explore-loading');
  var emptyState = document.getElementById('explore-empty-state');
  var emptyMessage = document.getElementById('explore-empty-message');
  var graphCanvasEl = document.getElementById('graph-canvas');
  var graphEyebrow = document.getElementById('graph-eyebrow');

  // Entity detail panel (Story 6.2). The panel element stays permanently in
  // the DOM — opening/closing/swapping only toggles the `.is-open` class and
  // updates specific child elements' textContent, never a wholesale
  // textContent/innerHTML overwrite of the panel itself (Story 6.1's own
  // review-caught pitfall — see the spec's Design Notes).
  var entityDetailPanel = document.getElementById('entity-detail-panel');
  var entityDetailClose = document.getElementById('entity-detail-close');
  var entityDetailName = document.getElementById('entity-detail-name');
  var entityDetailType = document.getElementById('entity-detail-type');
  var entityDetailRelationships = document.getElementById('entity-detail-relationships');
  var entityDetailTags = document.getElementById('entity-detail-tags');
  var selectedIdentity = null;

  if (!graphCanvasEl) {
    return;
  }

  if (entityDetailClose) {
    entityDetailClose.addEventListener('click', function () {
      closeEntityDetailPanel();
    });
  }

  function closeEntityDetailPanel() {
    selectedIdentity = null;
    if (!entityDetailPanel) {
      return;
    }
    entityDetailPanel.classList.remove('is-open');
    entityDetailPanel.setAttribute('aria-hidden', 'true');
  }

  // Builds one line per Relationship involving `identity`, matching on
  // `sourceIdentity`/`targetIdentity` against the already-fetched
  // `body.relationships` (no new endpoint — AD-14's read-path philosophy).
  function relationshipLines(identity, relationships) {
    var lines = [];
    (relationships || []).forEach(function (relationship) {
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

  function renderRelationships(identity, relationships) {
    if (!entityDetailRelationships) {
      return;
    }
    entityDetailRelationships.textContent = '';
    var lines = relationshipLines(identity, relationships);
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

  function renderTags(type) {
    if (!entityDetailTags) {
      return;
    }
    entityDetailTags.textContent = '';
    // Always exactly one chip — the Entity's own `type` value stands in for
    // a Tag (human decision; no real Tag concept exists — see Design Notes).
    var chip = document.createElement('span');
    chip.className = 'node-detail-tag';
    chip.textContent = type || 'Unknown';
    entityDetailTags.appendChild(chip);
  }

  function openEntityDetailPanel(nodeData, relationships) {
    if (!entityDetailPanel) {
      return;
    }
    selectedIdentity = nodeData.identity;
    if (entityDetailName) {
      entityDetailName.textContent = nodeData.name || nodeData.identity;
    }
    if (entityDetailType) {
      entityDetailType.textContent = 'Type: ' + (nodeData.type || 'Unknown');
    }
    renderRelationships(nodeData.identity, relationships);
    renderTags(nodeData.type);
    entityDetailPanel.classList.add('is-open');
    entityDetailPanel.setAttribute('aria-hidden', 'false');
  }

  var NO_CORPUS_MESSAGE_HTML =
      'No Corpus has been ingested yet. <a href="/">Go to the main screen</a> to upload a Corpus or load the built-in demo dataset.';
  var LOAD_FAILED_MESSAGE_HTML =
      'The Knowledge Graph could not be loaded. Please try reloading the page.';

  // #explore-loading is visible from page load (in the static HTML) so the
  // page is never blank while the fetch below is in flight — AC2 requires a
  // plain-language state at all times, never neither canvas nor message.

  fetch('/api/graph')
    .then(function (response) {
      if (!response.ok) {
        throw new Error('Explore graph fetch failed with status ' + response.status);
      }
      return response.json();
    })
    .then(function (body) {
      var entities = (body && body.entities) || [];
      if (entities.length === 0) {
        showEmptyState(NO_CORPUS_MESSAGE_HTML);
        return;
      }
      renderGraph(body);
    })
    .catch(function (error) {
      // This page has no other error-reporting surface, and a broken/blank
      // canvas is never acceptable here (Story 6.1 AC2) — a failed fetch
      // still shows a plain-language state; the console message is only for
      // a developer diagnosing why it failed.
      console.error('Explore page could not load the Knowledge Graph.', error);
      showEmptyState(LOAD_FAILED_MESSAGE_HTML);
    });

  function showEmptyState(messageHtml) {
    if (loadingState) {
      loadingState.hidden = true;
    }
    if (emptyState) {
      emptyState.hidden = false;
    }
    if (emptyMessage && messageHtml) {
      emptyMessage.innerHTML = messageHtml;
    }
    if (graphCanvasEl) {
      graphCanvasEl.hidden = true;
      graphCanvasEl.setAttribute('aria-hidden', 'true');
    }
    if (graphEyebrow) {
      graphEyebrow.hidden = true;
    }
  }

  function renderGraph(body) {
    // Unlike the main screen, the Explore page enables pan/zoom and has no
    // Community-visualization toggle: hulls are unconditionally shown
    // (Story 6.1 AC1). `init()` returns null when Cytoscape itself failed
    // to load (e.g. the CDN script didn't fetch) — fall back to the empty
    // state rather than revealing a canvas with nothing drawable on it,
    // which would violate AC2's "never a blank/broken canvas".
    if (!window.GraphCanvas || !window.GraphCanvas.init({ interactive: true })) {
      showEmptyState(LOAD_FAILED_MESSAGE_HTML);
      return;
    }

    if (loadingState) {
      loadingState.hidden = true;
    }
    if (emptyState) {
      emptyState.hidden = true;
    }
    if (graphCanvasEl) {
      graphCanvasEl.hidden = false;
      graphCanvasEl.setAttribute('aria-hidden', 'false');
    }
    if (graphEyebrow) {
      graphEyebrow.hidden = false;
    }

    (body.entities || []).forEach(function (entity) {
      window.GraphCanvas.addEntity(entity.identity, entity.name, entity.type);
    });
    (body.relationships || []).forEach(function (relationship) {
      window.GraphCanvas.addRelationship(
          relationship.sourceIdentity, relationship.source,
          relationship.targetIdentity, relationship.target, relationship.type);
    });
    (body.communities || []).forEach(function (community) {
      window.GraphCanvas.addCommunity(
          community.communityId, community.summary, community.memberEntityIdentities);
    });

    window.GraphCanvas.setHullsVisible(true);

    // Entity detail panel wiring (Story 6.2): `body.relationships` is
    // already-fetched data held in this call's closure, so no per-click
    // network request is ever made. `onNodeTap`/`onBackgroundTap` are
    // opt-in registrations only `explore.js` uses — `upload.js` never
    // calls them, so the main screen is unaffected.
    if (typeof window.GraphCanvas.onNodeTap === 'function') {
      window.GraphCanvas.onNodeTap(function (nodeData) {
        if (!nodeData || !nodeData.identity) {
          return;
        }
        if (selectedIdentity === nodeData.identity) {
          closeEntityDetailPanel();
          return;
        }
        openEntityDetailPanel(nodeData, body.relationships || []);
      });
    }

    if (typeof window.GraphCanvas.onBackgroundTap === 'function') {
      window.GraphCanvas.onBackgroundTap(function () {
        closeEntityDetailPanel();
      });
    }
  }
})();
