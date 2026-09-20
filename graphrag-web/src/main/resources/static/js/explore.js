(function () {
  'use strict';

  var emptyState = document.getElementById('explore-empty-state');
  var graphCanvasEl = document.getElementById('graph-canvas');
  var graphEyebrow = document.getElementById('graph-eyebrow');

  if (!graphCanvasEl) {
    return;
  }

  fetch('/api/graph')
    .then(function (response) {
      return response.json();
    })
    .then(function (body) {
      var entities = (body && body.entities) || [];
      if (entities.length === 0) {
        showEmptyState();
        return;
      }
      renderGraph(body);
    })
    .catch(function () {
      // This page has no other error-reporting surface, and a broken/blank
      // canvas is never acceptable here (Story 6.1 AC2) — a failed fetch
      // is treated the same as "nothing to show yet".
      showEmptyState();
    });

  function showEmptyState() {
    if (emptyState) {
      emptyState.hidden = false;
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

    if (!window.GraphCanvas) {
      return;
    }

    // Unlike the main screen, the Explore page enables pan/zoom and has no
    // Community-visualization toggle: hulls are unconditionally shown
    // (Story 6.1 AC1).
    window.GraphCanvas.init({ interactive: true });

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
