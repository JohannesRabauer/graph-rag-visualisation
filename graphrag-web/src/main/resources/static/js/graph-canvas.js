(function () {
  'use strict';

  // Cytoscape has no built-in "hull" primitive. A pale compound parent node
  // per Community is the simplest dependency-free way to get a soft bounding
  // shape behind its member nodes: Cytoscape auto-sizes/positions the
  // parent's background around its children and keeps it updated as the
  // layout runs. Toggling the community view OFF just hides that background
  // (via a style class) while member nodes stay visible and stay parented,
  // so toggling back ON needs no re-fetch and no replay of the fold-in.
  var COMMUNITY_TOKEN_COUNT = 10;
  var HULL_HIDDEN_CLASS = 'hull-hidden';

  var cy = null;
  var hullsVisible = true;
  var communityLegendEntries = {};
  var layoutQueued = false;
  // Opt-in tap registrations (Story 6.2). Only `explore.js` calls
  // `onNodeTap`/`onBackgroundTap`; `upload.js` never does, so the main
  // screen's own canvas and Story 5.2's Replay click-to-highlight wiring
  // are unaffected. Stored as module state (not re-wired per `init()` call)
  // so registering before or after `init()` both work.
  var nodeTapCallback = null;
  var backgroundTapCallback = null;
  // Roving-focus state for keyboard navigation (see `init`'s container
  // keydown wiring below): the id of the node currently carrying the
  // visual `.kbd-focus` ring, kept as module state so it survives between
  // keydown events without re-querying the DOM each time.
  var focusedNodeId = null;

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

  // The legend is far more useful naming *what a community is about* than
  // showing its opaque internal id (e.g. "community-7") — trims the AI/
  // deterministic summary down to a short label, falling back to the raw
  // id only when no summary was provided.
  var LEGEND_LABEL_MAX_LENGTH = 42;

  function legendLabel(communityId, summary) {
    var text = (summary || '').trim();
    if (!text) {
      return communityId;
    }
    if (text.length <= LEGEND_LABEL_MAX_LENGTH) {
      return text;
    }
    return text.slice(0, LEGEND_LABEL_MAX_LENGTH - 1).trim() + '…';
  }

  function init(options) {
    // `interactive` gates user pan/zoom (Story 6.1's Explore page passes
    // `{ interactive: true }`); the default stays `false`, matching the
    // main screen's existing pan/zoom-disabled behavior unchanged
    // (Story 6.1 AC4 — a regression guard for this shared module).
    var interactive = !!(options && options.interactive);

    var container = graphContainer();
    if (cy) {
      cy.destroy();
      cy = null;
    }

    communityLegendEntries = {};
    hullsVisible = true;
    focusedNodeId = null;
    renderLegend();

    if (!container || typeof window.cytoscape === 'undefined') {
      return null;
    }

    // Canvas rendering has no native per-node DOM elements to give a
    // screen reader/keyboard user, so the container itself becomes one
    // big focusable, keyboard-operable region (a roving-focus pattern):
    // Tab focuses the graph once, arrow keys step between nodes
    // (Entities and Community hulls alike), Enter/Space "clicks" whatever
    // is focused. Only wire the DOM listeners once per container element.
    if (!container.hasAttribute('tabindex')) {
      container.setAttribute('tabindex', '0');
      container.setAttribute('role', 'application');
      container.setAttribute(
        'aria-label',
        'Knowledge Graph. Use arrow keys to move between entities and communities, Enter to select.');
      container.addEventListener('focus', handleContainerFocus);
      container.addEventListener('blur', handleContainerBlur);
      container.addEventListener('keydown', handleContainerKeydown);
    }

    cy = window.cytoscape({
      container: container,
      elements: [],
      userZoomingEnabled: interactive,
      userPanningEnabled: interactive,
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
          // stay driven by its children's layout, not a fixed size. Also
          // restores background-opacity/label over `.hull-hidden` (below,
          // earlier in this array) — a Retrieval Trace step touching a
          // Community must be visible during Replay even if the community-
          // visualization toggle is currently OFF, otherwise the step
          // advances with nothing to see (deferred-work: Story 5.2 gap).
          // This forces only the specific hull(s) a step touches; every
          // other hidden hull is untouched, so the toggle's "with vs.
          // without" comparison still works once Replay closes.
          selector: 'node.step-active.community-hull',
          style: {
            'border-color': readCssVar('--active', '#E85D2B'),
            'border-width': 3,
            'background-opacity': 0.6,
            label: 'data(label)'
          }
        },
        {
          selector: 'node.step-previous.community-hull',
          style: {
            'border-color': readCssVar('--accent', '#2563EB'),
            'border-width': 2.5,
            'background-opacity': 0.6,
            label: 'data(label)'
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
        },
        {
          // Clicking (or Enter/Space-activating) a Community hull zooms the
          // viewport to fit that community and marks it with a bold ring, so
          // "which community is selected" is visible without relying on the
          // legend alone.
          selector: 'node.community-focused',
          style: {
            'border-width': 3.5,
            'border-color': readCssVar('--accent', '#2563EB')
          }
        },
        {
          // Keyboard roving-focus ring (see `init`'s container keydown
          // wiring): a dashed outline distinct from both the mouse-driven
          // `.community-focused` ring and Replay's `.step-active` ring, so
          // "keyboard focus" never reads as "selected"/"currently replaying".
          selector: 'node.kbd-focus',
          style: {
            'border-style': 'dashed',
            'border-width': 3,
            'border-color': readCssVar('--ink-900', '#14181C')
          }
        }
      ]
    });

    // Entity node clicks (Story 6.2): `.community-hull` compound nodes are
    // routed to `focusCommunity` instead of the Entity detail callback, so
    // both node types are clickable/navigable, just to different effect.
    cy.on('tap', 'node', function (evt) {
      var node = evt.target;
      setKeyboardFocus(node.id());
      if (node.hasClass('community-hull')) {
        focusCommunity(communityIdFromParentId(node.id()));
        return;
      }
      if (nodeTapCallback) {
        nodeTapCallback({
          identity: node.id(),
          name: node.data('name'),
          type: node.data('type')
        });
      }
    });

    // Background clicks: bound without a selector directly on `cy`, this
    // also receives bubbled taps on nodes/edges, so it must check
    // `evt.target === cy` (the standard Cytoscape idiom) to isolate an
    // actual empty-canvas tap. Clearing community focus + refitting the
    // whole graph here is a built-in behavior (not gated on
    // `backgroundTapCallback`, which only `explore.js` registers) so both
    // pages get "tap empty space to reset the view" for free.
    cy.on('tap', function (evt) {
      if (evt.target !== cy) {
        return;
      }
      clearCommunityFocus();
      if (backgroundTapCallback) {
        backgroundTapCallback();
      }
    });

    return cy;
  }

  // Extracts the raw communityId back out of a hull node's `community::`-
  // prefixed Cytoscape id (the inverse of `addCommunity`'s `parentId`).
  function communityIdFromParentId(parentId) {
    var prefix = 'community::';
    return String(parentId || '').indexOf(prefix) === 0
      ? parentId.slice(prefix.length)
      : parentId;
  }

  function clearCommunityFocus() {
    if (!cy) {
      return;
    }
    cy.nodes('.community-hull').removeClass('community-focused');
    setLegendActiveCommunity(null);
  }

  // Zooms/fits the viewport to one Community's members and marks it as
  // selected on both the canvas (bold ring) and the legend (active chip) —
  // the shared behavior behind clicking a hull, activating it via keyboard,
  // and clicking its legend entry (see `renderLegend`).
  function focusCommunity(communityId) {
    if (!cy || !communityId) {
      return;
    }
    var hull = cy.getElementById('community::' + communityId);
    if (!hull || hull.length === 0) {
      return;
    }
    cy.nodes('.community-hull').removeClass('community-focused');
    hull.addClass('community-focused');
    setLegendActiveCommunity(communityId);
    cy.animate(
      { fit: { eles: hull.union(hull.descendants()), padding: 40 } },
      { duration: 300 }
    );
  }

  function setLegendActiveCommunity(communityId) {
    var legend = legendContainer();
    if (!legend) {
      return;
    }
    Array.prototype.forEach.call(legend.querySelectorAll('.graph-legend-item'), function (item) {
      var isActive = !!communityId && item.dataset.communityId === communityId;
      item.classList.toggle('is-active', isActive);
      item.setAttribute('aria-pressed', isActive ? 'true' : 'false');
    });
  }

  function onNodeTap(callback) {
    nodeTapCallback = typeof callback === 'function' ? callback : null;
  }

  function onBackgroundTap(callback) {
    backgroundTapCallback = typeof callback === 'function' ? callback : null;
  }

  // ---- keyboard navigation (roving focus across all nodes) ----
  // Returns every node in a stable order (Cytoscape's own collection
  // order, effectively insertion order) — Entities and Community hulls
  // are both included, since both must be keyboard-reachable.
  function orderedNodes() {
    return cy ? cy.nodes().toArray() : [];
  }

  function setKeyboardFocus(nodeId) {
    if (!cy) {
      return;
    }
    if (focusedNodeId) {
      var previous = cy.getElementById(focusedNodeId);
      if (previous && previous.length > 0) {
        previous.removeClass('kbd-focus');
      }
    }
    focusedNodeId = nodeId || null;
    if (focusedNodeId) {
      var next = cy.getElementById(focusedNodeId);
      if (next && next.length > 0) {
        next.addClass('kbd-focus');
        cy.animate({ center: { eles: next } }, { duration: 150 });
      }
    }
  }

  function moveKeyboardFocus(delta) {
    var nodes = orderedNodes();
    if (nodes.length === 0) {
      return;
    }
    var currentIndex = focusedNodeId
      ? nodes.findIndex(function (node) { return node.id() === focusedNodeId; })
      : -1;
    var nextIndex = currentIndex === -1
      ? 0
      : (currentIndex + delta + nodes.length) % nodes.length;
    setKeyboardFocus(nodes[nextIndex].id());
  }

  // Enter/Space "clicks" whatever currently has keyboard focus — the same
  // effect a mouse tap on that node would have (Entity detail callback, or
  // `focusCommunity` for a hull).
  function activateKeyboardFocus() {
    if (!cy || !focusedNodeId) {
      return;
    }
    var node = cy.getElementById(focusedNodeId);
    if (!node || node.length === 0) {
      return;
    }
    if (node.hasClass('community-hull')) {
      focusCommunity(communityIdFromParentId(node.id()));
      return;
    }
    if (nodeTapCallback) {
      nodeTapCallback({
        identity: node.id(),
        name: node.data('name'),
        type: node.data('type')
      });
    }
  }

  function handleContainerFocus() {
    if (!focusedNodeId) {
      moveKeyboardFocus(0);
    }
  }

  function handleContainerBlur() {
    setKeyboardFocus(null);
  }

  function handleContainerKeydown(event) {
    switch (event.key) {
      case 'ArrowRight':
      case 'ArrowDown':
        event.preventDefault();
        moveKeyboardFocus(1);
        break;
      case 'ArrowLeft':
      case 'ArrowUp':
        event.preventDefault();
        moveKeyboardFocus(-1);
        break;
      case 'Home':
        event.preventDefault();
        setKeyboardFocus(orderedNodes()[0] ? orderedNodes()[0].id() : null);
        break;
      case 'End':
        var nodes = orderedNodes();
        event.preventDefault();
        setKeyboardFocus(nodes.length > 0 ? nodes[nodes.length - 1].id() : null);
        break;
      case 'Enter':
      case ' ':
      case 'Spacebar':
        event.preventDefault();
        activateKeyboardFocus();
        break;
      case 'Escape':
        clearCommunityFocus();
        break;
      default:
        break;
    }
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
      name: legendLabel(communityId, summary),
      fullSummary: summary || communityId,
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

      // A legend chip is a second, always-visible way to reach a Community
      // (`focusCommunity`) besides tapping its hull directly on the canvas
      // — a real `<button>` so it is natively focusable/clickable/keyboard
      // operable without any custom keydown wiring.
      var item = document.createElement('button');
      item.type = 'button';
      item.className = 'graph-legend-item';
      item.title = entry.fullSummary || entry.name;
      item.setAttribute('aria-label', entry.fullSummary || entry.name);
      item.setAttribute('aria-pressed', 'false');
      item.dataset.communityId = communityId;
      item.addEventListener('click', function () {
        focusCommunity(communityId);
      });

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

  // Resolves a RetrievalStep's node id on the Cytoscape canvas. ENTITY steps
  // use the same `normalizedIdentity()` string Story 4.3's SSE events
  // already use as node ids; COMMUNITY steps use the `community::` + id
  // prefix `addCommunity` gives its compound parent node. RELATIONSHIP
  // steps have no node of their own — see `parseRelationshipEdgeId` and
  // `highlightRetrievalStep` below, which resolve them as edges instead.
  function stepNodeId(step) {
    if (!step || !step.identifier) {
      return null;
    }
    return step.kind === 'COMMUNITY' ? 'community::' + step.identifier : step.identifier;
  }

  // A RELATIONSHIP step's `identifier` is built (AnswerLocalSearch) with
  // the exact same `sourceIdentity->type->targetIdentity` convention
  // `addRelationship` below uses for the rendered edge's own id, so it
  // resolves directly to a real edge rather than needing to be inferred
  // from adjacent ENTITY steps.
  function parseRelationshipEdgeId(identifier) {
    var parts = String(identifier || '').split('->');
    return parts.length === 3
      ? { sourceIdentity: parts[0], targetIdentity: parts[2] }
      : null;
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
    // read as an upcoming/dashed preview. Skipped for any pair touching a
    // RELATIONSHIP step: that step is already an edge, not a node either
    // side of one, so there's nothing for findEdgesBetween to bridge.
    for (var i = currentIndex; i < steps.length - 1; i += 1) {
      if (steps[i].kind !== 'RELATIONSHIP' && steps[i + 1].kind !== 'RELATIONSHIP') {
        findEdgesBetween(stepNodeId(steps[i]), stepNodeId(steps[i + 1])).forEach(function (edge) {
          edge.addClass('edge-upcoming');
        });
      }
    }

    if (current) {
      highlightRetrievalStep(current, 'step-active');
    }

    if (previous) {
      highlightRetrievalStep(previous, 'step-previous');
    }

    if (previous && current && previous.kind !== 'RELATIONSHIP' && current.kind !== 'RELATIONSHIP') {
      findEdgesBetween(stepNodeId(previous), stepNodeId(current)).forEach(function (edge) {
        edge.removeClass('edge-upcoming');
        edge.addClass('edge-traversed');
      });
    }
  }

  // Applies a Replay step's highlight. A RELATIONSHIP step names a real
  // rendered edge directly (see `parseRelationshipEdgeId`) — mark it
  // traversed and ring both of its endpoint Entities with the same class a
  // plain ENTITY step's own node would get, so the hop reads clearly as one
  // unit. Any other kind just highlights its own node, as before.
  function highlightRetrievalStep(step, nodeClass) {
    if (!cy || !step) {
      return;
    }
    if (step.kind === 'RELATIONSHIP') {
      var edge = cy.getElementById(step.identifier);
      if (edge && edge.length > 0) {
        edge.addClass('edge-traversed');
      }
      var endpoints = parseRelationshipEdgeId(step.identifier);
      if (endpoints) {
        var sourceNode = cy.getElementById(endpoints.sourceIdentity);
        var targetNode = cy.getElementById(endpoints.targetIdentity);
        if (sourceNode && sourceNode.length > 0) {
          sourceNode.addClass(nodeClass);
        }
        if (targetNode && targetNode.length > 0) {
          targetNode.addClass(nodeClass);
        }
      }
      return;
    }
    var node = cy.getElementById(stepNodeId(step));
    if (node && node.length > 0) {
      node.addClass(nodeClass);
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

  // Test-support only: fires a real Cytoscape 'tap' event on a rendered node
  // (entity id or `community::<id>` hull) or, with no identity, on the
  // background — exercising the exact same `cy.on('tap', ...)` handler
  // wiring (including the hull-vs-entity branching in `init`'s own tap
  // listener) that a real pointer click would, without depending on pixel
  // coordinates translated through a still-animating force-directed layout
  // (`queueLayout`'s 'cose' layout), which proved flaky under headless
  // browser automation. Returns false if the node isn't currently rendered.
  function simulateTap(identity) {
    if (!cy) {
      return false;
    }
    if (!identity) {
      cy.emit('tap');
      return true;
    }
    var node = cy.getElementById(identity);
    if (!node || node.length === 0) {
      return false;
    }
    node.emit('tap');
    return true;
  }

  // Test-support only: the actual rendered background-opacity Cytoscape's
  // style cascade resolves for a Community hull (0 when hidden by the
  // toggle, restored to ~0.6 when visible — whether because the toggle is
  // ON or because Replay is forcing this specific hull visible for a
  // Community step). Reading the resolved style, rather than re-deriving
  // "should this be visible" from class names, is what actually determines
  // what a viewer sees on screen.
  function communityHullOpacity(communityId) {
    if (!cy || !communityId) {
      return null;
    }
    var hull = cy.getElementById('community::' + communityId);
    if (!hull || hull.length === 0) {
      return null;
    }
    return hull.numericStyle('background-opacity');
  }

  // Test-support only: whether a rendered element (node, hull, or edge —
  // Cytoscape ids are unique across all of them) currently carries a given
  // class. Generic on purpose, so it covers any future highlight class
  // without a new single-purpose hook each time — e.g. asserting a
  // RetrievalStep's edge actually got `edge-traversed` during Replay.
  function elementHasClass(elementId, className) {
    if (!cy || !elementId) {
      return false;
    }
    var element = cy.getElementById(elementId);
    return !!(element && element.length > 0 && element.hasClass(className));
  }

  window.GraphCanvas = {
    init: init,
    addEntity: addEntity,
    addRelationship: addRelationship,
    addCommunity: addCommunity,
    setHullsVisible: setHullsVisible,
    highlightStep: highlightStep,
    clearStepHighlights: clearStepHighlights,
    onNodeTap: onNodeTap,
    onBackgroundTap: onBackgroundTap,
    focusCommunity: focusCommunity,
    simulateTap: simulateTap,
    communityHullOpacity: communityHullOpacity,
    elementHasClass: elementHasClass
  };
})();
