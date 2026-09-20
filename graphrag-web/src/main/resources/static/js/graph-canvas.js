(function () {
  'use strict';

  // Cytoscape has no built-in "hull" primitive. A pale compound parent node
  // per Community is the simplest dependency-free way to get a soft bounding
  // shape behind its member nodes: Cytoscape auto-sizes/positions the
  // parent's background around its children and keeps it updated as the
  // layout runs. Toggling the community view OFF just hides that background
  // (via a style class) while member nodes stay visible and stay parented,
  // so toggling back ON needs no re-fetch and no replay of the fold-in.
  var COMMUNITY_TOKEN_COUNT = 5;
  var HULL_HIDDEN_CLASS = 'hull-hidden';

  var cy = null;
  var hullsVisible = true;
  var communityLegendEntries = {};
  var layoutQueued = false;

  function graphContainer() {
    return document.getElementById('graph-canvas');
  }

  function legendContainer() {
    return document.getElementById('graph-legend');
  }

  function readCssVar(name, fallback) {
    var value = getComputedStyle(document.documentElement).getPropertyValue(name);
    value = value ? value.trim() : '';
    return value || fallback;
  }

  // Derives a stable color index from a hash of the full communityId string
  // rather than parsing a numeric suffix off it, so this keeps working
  // whatever id format community detection produces.
  function communityColorIndex(communityId) {
    var text = String(communityId || '');
    var hash = 0;
    for (var i = 0; i < text.length; i += 1) {
      hash = (hash * 31 + text.charCodeAt(i)) >>> 0;
    }
    return (hash % COMMUNITY_TOKEN_COUNT) + 1;
  }

  function communityColors(communityId) {
    var index = communityColorIndex(communityId);
    return {
      fill: readCssVar('--community-' + index, '#DCE9FD'),
      labelColor: readCssVar('--community-' + index + '-label', '#5C82C4')
    };
  }

  function init() {
    var container = graphContainer();
    if (cy) {
      cy.destroy();
      cy = null;
    }

    communityLegendEntries = {};
    hullsVisible = true;
    renderLegend();

    if (!container || typeof window.cytoscape === 'undefined') {
      return null;
    }

    cy = window.cytoscape({
      container: container,
      elements: [],
      style: [
        {
          selector: 'node',
          style: {
            'background-color': readCssVar('--node-fill', '#FFFFFF'),
            'border-color': readCssVar('--node-line', '#4B5563'),
            'border-width': 1.5,
            label: 'data(label)',
            'font-size': 9,
            'font-family': readCssVar('--font-ui', 'sans-serif'),
            color: readCssVar('--ink-900', '#14181C'),
            'text-valign': 'bottom',
            'text-margin-y': 4,
            width: 18,
            height: 18
          }
        },
        {
          selector: 'edge',
          style: {
            width: 1,
            'line-color': readCssVar('--node-line', '#4B5563'),
            'target-arrow-color': readCssVar('--node-line', '#4B5563'),
            'target-arrow-shape': 'triangle',
            'arrow-scale': 0.7,
            'curve-style': 'bezier'
          }
        },
        {
          selector: 'node.community-hull',
          style: {
            shape: 'round-rectangle',
            'background-color': 'data(hullFill)',
            'background-opacity': 0.6,
            'border-width': 0,
            label: 'data(label)',
            'text-valign': 'top',
            'text-halign': 'center',
            'text-transform': 'uppercase',
            'font-family': readCssVar('--font-mono', 'monospace'),
            'font-size': 8,
            color: 'data(labelColor)',
            padding: '20px'
          }
        },
        {
          selector: 'node.community-hull.' + HULL_HIDDEN_CLASS,
          style: {
            'background-opacity': 0,
            label: ''
          }
        },
        {
          // Replay's "current step" node (DESIGN.md `components.node-active`):
          // the larger warm-active ring. Wins over `.step-previous` when a
          // node somehow carries both (shouldn't happen — `clearStepHighlights`
          // strips both before every `highlightStep` call). Excludes
          // `.community-hull`: a compound parent's size is driven by its
          // children, so forcing a fixed width/height on it here would break
          // that auto-sizing (it gets its own border-only treatment below).
          selector: 'node.step-active:not(.community-hull)',
          style: {
            'background-color': readCssVar('--active-soft', '#FFE9DE'),
            'border-color': readCssVar('--active', '#E85D2B'),
            'border-width': 2.5,
            width: 28,
            height: 28,
            'z-index': 10
          }
        },
        {
          // Replay's "previous step" node (`components.node-previous-step`):
          // a distinct, smaller accent ring so it reads as "just visited",
          // not "current". Same compound-node exclusion as `.step-active`.
          selector: 'node.step-previous:not(.community-hull)',
          style: {
            'background-color': readCssVar('--accent-soft', '#DCE7FD'),
            'border-color': readCssVar('--accent', '#2563EB'),
            'border-width': 2,
            width: 22,
            height: 22,
            'z-index': 9
          }
        },
        {
          // Compound (community-hull) version of the same two states: only
          // the border changes, since a compound node's width/height must
          // stay driven by its children's layout, not a fixed size.
          selector: 'node.step-active.community-hull',
          style: {
            'border-color': readCssVar('--active', '#E85D2B'),
            'border-width': 3
          }
        },
        {
          selector: 'node.step-previous.community-hull',
          style: {
            'border-color': readCssVar('--accent', '#2563EB'),
            'border-width': 2.5
          }
        },
        {
          // The edge directly connecting the previous and current step's
          // entities, when one already exists among the rendered
          // Relationships (`components.edge-traversed`) — opportunistic,
          // per Story 5.2's Design Notes, never guaranteed for every step.
          selector: 'edge.edge-traversed',
          style: {
            'line-color': readCssVar('--active', '#E85D2B'),
            'target-arrow-color': readCssVar('--active', '#E85D2B'),
            width: 2.5,
            'z-index': 10
          }
        },
        {
          // A preview of edges connecting OTHER consecutive step pairs in the
          // same trace that also happen to exist in the rendered graph, drawn
          // dashed so Replay reads as "here's the shape of the trace ahead",
          // never mistaken for the currently-traversed edge
          // (`components.edge-upcoming`).
          selector: 'edge.edge-upcoming',
          style: {
            'line-style': 'dashed',
            'line-dash-pattern': [3, 3],
            'line-color': readCssVar('--node-line', '#4B5563'),
            width: 1
          }
        }
      ]
    });

    return cy;
  }

  function ensureNode(identity, fallbackLabel) {
    if (!cy || !identity) {
      return null;
    }
    var existing = cy.getElementById(identity);
    if (existing && existing.length > 0) {
      return existing;
    }
    return cy.add({
      group: 'nodes',
      data: { id: identity, label: fallbackLabel || identity, placeholder: true }
    });
  }

  function addEntity(identity, name, type) {
    if (!cy || !identity) {
      return;
    }
    var label = name || identity;
    var node = cy.getElementById(identity);
    if (node && node.length > 0) {
      // A placeholder node may already exist here, created early by a
      // relationship or community that referenced this identity before its
      // own entity-extracted event arrived. Upgrade it instead of no-op'ing
      // so it doesn't stay stuck showing the raw identity string.
      node.data('label', label);
      node.data('name', name);
      node.data('type', type);
      node.data('placeholder', false);
    } else {
      cy.add({
        group: 'nodes',
        data: { id: identity, label: label, name: name, type: type, placeholder: false }
      });
    }
    queueLayout();
  }

  function addRelationship(sourceIdentity, source, targetIdentity, target, type) {
    if (!cy || !sourceIdentity || !targetIdentity) {
      return;
    }
    ensureNode(sourceIdentity, source);
    ensureNode(targetIdentity, target);

    var edgeId = sourceIdentity + '->' + (type || 'related_to') + '->' + targetIdentity;
    if (cy.getElementById(edgeId).length === 0) {
      cy.add({
        group: 'edges',
        data: { id: edgeId, source: sourceIdentity, target: targetIdentity, label: type || '' }
      });
    }
    queueLayout();
  }

  function addCommunity(communityId, summary, memberEntityIdentities) {
    if (!cy || !communityId) {
      return;
    }
    var colors = communityColors(communityId);
    var parentId = 'community::' + communityId;

    if (cy.getElementById(parentId).length === 0) {
      cy.add({
        group: 'nodes',
        data: {
          id: parentId,
          label: communityId,
          summary: summary,
          hullFill: colors.fill,
          labelColor: colors.labelColor
        },
        classes: 'community-hull' + (hullsVisible ? '' : ' ' + HULL_HIDDEN_CLASS)
      });
    }

    (memberEntityIdentities || []).forEach(function (identity) {
      var member = ensureNode(identity, identity);
      if (member && member.length > 0) {
        member.move({ parent: parentId });
      }
    });

    communityLegendEntries[communityId] = {
      name: communityId,
      fill: colors.fill,
      labelColor: colors.labelColor
    };
    renderLegend();
    queueLayout();
  }

  function setHullsVisible(visible) {
    hullsVisible = !!visible;
    if (cy) {
      cy.nodes('.community-hull').forEach(function (node) {
        node.toggleClass(HULL_HIDDEN_CLASS, !hullsVisible);
      });
    }
    renderLegend();
  }

  function renderLegend() {
    var legend = legendContainer();
    if (!legend) {
      return;
    }
    legend.textContent = '';

    var communityIds = Object.keys(communityLegendEntries);
    var shouldShow = hullsVisible && communityIds.length > 0;

    communityIds.forEach(function (communityId) {
      var entry = communityLegendEntries[communityId];

      var item = document.createElement('span');
      item.className = 'graph-legend-item';

      var swatch = document.createElement('span');
      swatch.className = 'graph-legend-swatch';
      swatch.style.background = entry.fill;
      swatch.style.borderColor = entry.labelColor;
      swatch.setAttribute('aria-hidden', 'true');
      item.appendChild(swatch);

      var name = document.createElement('span');
      name.className = 'graph-legend-name';
      name.textContent = entry.name;
      item.appendChild(name);

      legend.appendChild(item);
    });

    legend.hidden = !shouldShow;
    legend.setAttribute('aria-hidden', shouldShow ? 'false' : 'true');
  }

  // Resolves a RetrievalStep's node id on the Cytoscape canvas. ENTITY (and
  // any future RELATIONSHIP) steps use the same `normalizedIdentity()`
  // string Story 4.3's SSE events already use as node ids; COMMUNITY steps
  // use the `community::` + id prefix `addCommunity` gives its compound
  // parent node.
  function stepNodeId(step) {
    if (!step || !step.identifier) {
      return null;
    }
    return step.kind === 'COMMUNITY' ? 'community::' + step.identifier : step.identifier;
  }

  // Looks for every edge connecting the two given node ids in either
  // direction (there can be more than one parallel Relationship between the
  // same two Entities). Deliberately matches on the edge's `source`/`target`
  // data rather than reconstructing `addRelationship`'s exact `id` string
  // (which also encodes the relationship type, unknown to a RetrievalStep)
  // — same underlying convention, direction-agnostic lookup.
  function findEdgesBetween(idA, idB) {
    if (!cy || !idA || !idB) {
      return [];
    }
    var found = [];
    cy.edges().forEach(function (edge) {
      var source = edge.data('source');
      var target = edge.data('target');
      if ((source === idA && target === idB) || (source === idB && target === idA)) {
        found.push(edge);
      }
    });
    return found;
  }

  function clearStepHighlights() {
    if (!cy) {
      return;
    }
    cy.nodes().removeClass('step-active step-previous');
    cy.edges().removeClass('edge-traversed edge-upcoming');
  }

  // Highlights the current Replay step (and the previous one) on the
  // Cytoscape canvas. `steps` is the full ordered RetrievalStep list from
  // `GET /api/traces/{traceId}`; `currentIndex` is the step Replay is
  // currently showing. Passing the whole list (rather than just the two
  // steps involved) lets this also draw the dashed "upcoming" preview for
  // any other consecutive-step edge that already exists in the graph.
  function highlightStep(steps, currentIndex) {
    if (!cy || !steps || steps.length === 0) {
      clearStepHighlights();
      return;
    }

    clearStepHighlights();

    var current = steps[currentIndex];
    var previous = currentIndex > 0 ? steps[currentIndex - 1] : null;

    // Only pairs from the current step onward are "not yet reached" — a
    // pair entirely behind currentIndex was already passed and should not
    // read as an upcoming/dashed preview.
    for (var i = currentIndex; i < steps.length - 1; i += 1) {
      findEdgesBetween(stepNodeId(steps[i]), stepNodeId(steps[i + 1])).forEach(function (edge) {
        edge.addClass('edge-upcoming');
      });
    }

    if (current) {
      var currentNode = cy.getElementById(stepNodeId(current));
      if (currentNode && currentNode.length > 0) {
        currentNode.addClass('step-active');
      }
    }

    if (previous) {
      var previousNode = cy.getElementById(stepNodeId(previous));
      if (previousNode && previousNode.length > 0) {
        previousNode.addClass('step-previous');
      }
    }

    if (previous && current) {
      findEdgesBetween(stepNodeId(previous), stepNodeId(current)).forEach(function (edge) {
        edge.removeClass('edge-upcoming');
        edge.addClass('edge-traversed');
      });
    }
  }

  function queueLayout() {
    if (!cy || layoutQueued) {
      return;
    }
    layoutQueued = true;
    var runLayout = function () {
      layoutQueued = false;
      if (!cy) {
        return;
      }
      cy.layout({ name: 'cose', animate: true, animationDuration: 400, fit: true, padding: 32, randomize: false }).run();
    };
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(runLayout);
    } else {
      setTimeout(runLayout, 0);
    }
  }

  window.GraphCanvas = {
    init: init,
    addEntity: addEntity,
    addRelationship: addRelationship,
    addCommunity: addCommunity,
    setHullsVisible: setHullsVisible,
    highlightStep: highlightStep,
    clearStepHighlights: clearStepHighlights
  };
})();
