(function () {
  'use strict';

  var loadingState = document.getElementById('explore-loading');
  var emptyState = document.getElementById('explore-empty-state');
  var emptyMessage = document.getElementById('explore-empty-message');
  var graphCanvasEl = document.getElementById('graph-canvas');
  var graphEyebrow = document.getElementById('graph-eyebrow');

  if (!graphCanvasEl) {
    return;
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
  }
})();
