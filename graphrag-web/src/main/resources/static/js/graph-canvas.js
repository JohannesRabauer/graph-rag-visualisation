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
  var ENTITY_TYPE_TOKEN_COUNT = 6;

  var cy = null;
  var hullsVisible = true;
  // Entity-type color-coding toggle (spec-10-3): default ON, matching the
  // community-visualization toggle's own default-ON convention.
  var entityTypeColoringEnabled = true;
  var communityLegendEntries = {};
  var layoutQueued = false;
  // Opt-in tap registrations (Story 6.2). Only `explore.js` calls
  // `onNodeTap`/`onBackgroundTap`; `upload.js` never does, so the main
  // screen's own canvas and Story 5.2's Replay click-to-highlight wiring
  // are unaffected. Stored as module state (not re-wired per `init()` call)
  // so registering before or after `init()` both work.
  var nodeTapCallback = null;
  var communityTapCallback = null;
  var backgroundTapCallback = null;
  // Roving-focus state for keyboard navigation (see `init`'s container
  // keydown wiring below): the id of the node currently carrying the
  // visual `.kbd-focus` ring, kept as module state so it survives between
  // keydown events without re-querying the DOM each time.
  var focusedNodeId = null;
  // Story 11.1: the container's own ResizeObserver (not a `window` `resize`
  // listener — see Design Notes in spec-11-1) so the canvas tracks real
  // window/viewport resizes and stays robust against any future CSS change
  // to how `#graph-canvas`'s own box is sized, without depending on that
  // change also firing a window resize event.
  // Kept as module state so a later `init()` call (which tears down and
  // rebuilds `cy`) can disconnect the previous observer before attaching a
  // fresh one, rather than stacking duplicate observers on the same
  // `#graph-canvas` element across re-inits.
  var resizeObserver = null;
  var resizeFitTimer = null;
  var RESIZE_FIT_DEBOUNCE_MS = 120;

  function graphContainer() {
    return document.getElementById('graph-canvas');
  }

  function legendContainer() {
    return document.getElementById('graph-legend');
  }

  function emptyStateContainer() {
    return document.getElementById('graph-canvas-empty');
  }

  // Shows/hides the "nothing to see" placeholder over #graph-canvas. Passing
  // no text (or an empty one) hides it — called once the first Entity or
  // Community actually renders, so the placeholder never lingers over real
  // graph content.
  function setEmptyState(message) {
    var el = emptyStateContainer();
    if (!el) {
      return;
    }
    if (message) {
      el.textContent = message;
      el.hidden = false;
      el.setAttribute('aria-hidden', 'false');
    } else {
      el.hidden = true;
      el.setAttribute('aria-hidden', 'true');
      el.textContent = '';
    }
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

  // Same deterministic-hash technique as `communityColorIndex`/
  // `communityColors` above, keyed on the Entity's own `type` string (e.g.
  // "Person", "Concept") rather than a communityId, and on the 6
  // `--entity-type-N` tokens rather than the 10 `--community-N` ones.
  function entityTypeColorIndex(type) {
    // Lowercase-normalized so "same type always the same color" still
    // holds when a real (non-stub) LLM free-forms the `type` string's case
    // differently across calls (e.g. "Person" vs. "person").
    var text = String(type || '').toLowerCase();
    var hash = 0;
    for (var i = 0; i < text.length; i += 1) {
      hash = (hash * 31 + text.charCodeAt(i)) >>> 0;
    }
    return (hash % ENTITY_TYPE_TOKEN_COUNT) + 1;
  }

  function entityTypeColors(type) {
    var index = entityTypeColorIndex(type);
    return {
      fill: readCssVar('--entity-type-' + index, '#7A8C6B'),
      labelColor: readCssVar('--entity-type-' + index + '-label', '#4F5C42')
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

    // Disconnect any observer/timer left by a prior `init()` call
    // unconditionally, before either early return below, so a call that
    // bails out (missing container, or Cytoscape not loaded) can never
    // leave a previous successful `init()`'s ResizeObserver still attached.
    if (resizeObserver) {
      resizeObserver.disconnect();
      resizeObserver = null;
    }
    if (resizeFitTimer) {
      clearTimeout(resizeFitTimer);
      resizeFitTimer = null;
    }

    var container = graphContainer();
    if (cy) {
      cy.destroy();
      cy = null;
    }

    communityLegendEntries = {};
    hullsVisible = true;
    entityTypeColoringEnabled = true;
    focusedNodeId = null;
    renderLegend();

    if (!container || typeof window.cytoscape === 'undefined') {
      // No silent blank grid: Cytoscape failed to load (e.g. its CDN script
      // was blocked or slow) — say so instead of leaving the canvas looking
      // broken with no explanation.
      setEmptyState('Knowledge graph view could not load in this browser session — try reloading the page.');
      return null;
    }

    // Starts empty until the first Entity/Community streams in — see
    // addEntity/addCommunity below, which clear this the moment either one
    // renders.
    setEmptyState('Nothing to see yet — entities will appear here as they’re extracted.');

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
      minZoom: 0.1,
      maxZoom: 5,
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
            height: 18,
            'transition-property': 'background-color border-color border-width width height',
            'transition-duration': '0.25s',
            'transition-timing-function': 'ease-in-out'
          }
        },
        {
          selector: 'edge',
          style: {
            'transition-property': 'line-color target-arrow-color width',
            'transition-duration': '0.25s',
            'transition-timing-function': 'ease-in-out',
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
          // Entity-type color-coding (spec-10-3): fill/border driven by the
          // `typeFill`/`typeBorder` data `addEntity` sets from
          // `entityTypeColors`, applied only when the `type-colored` class
          // is present (toggle ON). Positioned after the base `node`
          // selector and before `.step-active`/`.step-previous` below, so
          // Replay's own border override still wins over a type-colored
          // border — same style-array precedence-by-position convention the
          // base `node` selector already relies on.
          selector: 'node.type-colored:not(.community-hull)',
          style: {
            'background-color': 'data(typeFill)',
            'border-color': 'data(typeBorder)'
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
        },
        {
          // Story 9.4's entity-search "jump to" pulse — a brief accent ring
          // distinct from Replay's `.step-active` (warm/active color), so a
          // jump never reads as "this is what a trace touched".
          selector: 'node.entity-search-hit:not(.community-hull)',
          style: {
            'border-color': readCssVar('--accent', '#2563EB'),
            'border-width': 3,
            width: 26,
            height: 26,
            'z-index': 11
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
        activateCommunityHull(node);
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

    watchContainerResize(container);

    return cy;
  }

  // Keeps the Cytoscape canvas in sync with its container's actual box.
  // `cy.resize()` just recalculates Cytoscape's cached width/height and is
  // cheap, so it runs on every observed change; the follow-up `cy.fit()`
  // re-centers/re-zooms the rendered graph and is debounced so a burst of
  // observer callbacks (a window being dragged, a CSS transition on
  // `.node-detail-panel`) doesn't fight in-progress pan/zoom with repeated
  // fits. Guarded on `cy` so this safely no-ops before `init()` has created
  // one (or after a later `init()` has torn the old one down).
  function watchContainerResize(container) {
    // `init()` already disconnects/reset any prior observer/timer
    // unconditionally at its top (before either early return), so this is
    // just a defensive no-op guard against calling `watchContainerResize`
    // directly with one already attached.
    if (resizeObserver) {
      resizeObserver.disconnect();
      resizeObserver = null;
    }
    if (resizeFitTimer) {
      clearTimeout(resizeFitTimer);
      resizeFitTimer = null;
    }
    if (!container || typeof window.ResizeObserver === 'undefined') {
      return;
    }
    resizeObserver = new window.ResizeObserver(function () {
      if (!cy) {
        return;
      }
      cy.resize();
      if (resizeFitTimer) {
        clearTimeout(resizeFitTimer);
      }
      resizeFitTimer = setTimeout(function () {
        resizeFitTimer = null;
        if (!cy) {
          return;
        }
        cy.fit();
      }, RESIZE_FIT_DEBOUNCE_MS);
    });
    resizeObserver.observe(container);
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

  var entitySearchHitTimer = null;

  // Story 9.4: name-prefix search over every real (non-placeholder,
  // non-hull) Entity node currently on the canvas — used by
  // entity-search.js's own dropdown, kept here since only graph-canvas.js
  // has direct Cytoscape access to the live node set.
  function searchEntities(prefix) {
    if (!cy || !prefix) {
      return [];
    }
    var needle = String(prefix).toLowerCase();
    var results = [];
    cy.nodes().forEach(function (node) {
      if (node.hasClass('community-hull') || node.data('placeholder')) {
        return;
      }
      var name = node.data('name') || node.data('label') || '';
      if (name.toLowerCase().indexOf(needle) !== 0) {
        return;
      }
      results.push({ identity: node.id(), name: name, type: node.data('type') || '' });
    });
    return results;
  }

  // Pans/zooms to center one Entity and briefly highlights it, then opens
  // its detail panel exactly as a direct click would (same
  // `nodeTapCallback`) — "jump to" is deliberately just a scripted version
  // of clicking the node yourself, not a separate interaction to learn.
  function focusEntity(identity) {
    if (!cy || !identity) {
      return;
    }
    var node = cy.getElementById(identity);
    if (!node || node.length === 0 || node.hasClass('community-hull')) {
      return;
    }
    setKeyboardFocus(node.id());
    cy.animate({ center: { eles: node }, zoom: Math.max(cy.zoom(), 1.5) }, { duration: 300 });

    node.addClass('entity-search-hit');
    if (entitySearchHitTimer) {
      window.clearTimeout(entitySearchHitTimer);
    }
    entitySearchHitTimer = window.setTimeout(function () {
      node.removeClass('entity-search-hit');
      entitySearchHitTimer = null;
    }, 1500);

    if (nodeTapCallback) {
      nodeTapCallback({
        identity: node.id(),
        name: node.data('name'),
        type: node.data('type')
      });
    }
  }

  // Story 11.6 (#35): the visible zoom in/out/fit-to-view button cluster's
  // wiring. Zoom in/out use `cy.animate({ zoom })` — the exact eased-
  // animation precedent `focusEntity()` above already established (same
  // 300ms duration/easing) — rather than an instant `cy.zoom()` jump, so
  // button-driven zoom feels identical to existing interactions. A ~1.25x
  // step per click, symmetric in both directions (dividing, not multiplying
  // by a separate "zoom out factor") so one zoom-in followed by one
  // zoom-out returns to the original zoom level. `minZoom`/`maxZoom` are set
  // on the Cytoscape instance (see its constructor above) so repeated clicks
  // can't drive the zoom to a degenerate scale.
  var ZOOM_STEP_FACTOR = 1.25;
  var ZOOM_ANIMATION_DURATION = 300;
  // Guards `zoomIn`/`zoomOut` against overlapping animations: `cy.zoom()`
  // read at click time reflects whatever the in-flight animation has
  // reached *so far*, not its target, so a rapid double-click would read a
  // mid-animation value and silently collapse two clicks into one step
  // instead of compounding. While an animation is in flight, further clicks
  // are a no-op until the `complete` callback clears this flag.
  var zoomAnimationInProgress = false;

  function animateZoom(targetZoom) {
    zoomAnimationInProgress = true;
    cy.animate(
      { zoom: targetZoom },
      {
        duration: ZOOM_ANIMATION_DURATION,
        complete: function () {
          zoomAnimationInProgress = false;
        }
      }
    );
  }

  function zoomIn() {
    if (!cy || zoomAnimationInProgress) {
      return;
    }
    animateZoom(cy.zoom() * ZOOM_STEP_FACTOR);
  }

  function zoomOut() {
    if (!cy || zoomAnimationInProgress) {
      return;
    }
    animateZoom(cy.zoom() / ZOOM_STEP_FACTOR);
  }

  function fitToView() {
    if (!cy) {
      return;
    }
    // Clears any in-flight zoomIn/zoomOut animation first — otherwise that
    // animation can keep running after `cy.fit()` returns and silently
    // override its result once it finishes.
    cy.stop(true, true);
    zoomAnimationInProgress = false;
    cy.fit();
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

  function onCommunityTap(callback) {
    communityTapCallback = typeof callback === 'function' ? callback : null;
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
      activateCommunityHull(node);
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

  function activateCommunityHull(node) {
    var communityId = communityIdFromParentId(node.id());
    focusCommunity(communityId);
    if (!communityTapCallback) {
      return;
    }
    var legend = communityLegendEntries[communityId] || {};
    communityTapCallback({
      communityId: communityId,
      name: legend.name || communityId,
      summary: legend.fullSummary || node.data('summary') || '',
      members: node.children().map(function (child) {
        return {
          identity: child.id(),
          name: child.data('name') || child.id(),
          type: child.data('type')
        };
      })
    });
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
    setEmptyState(null);
    var label = name || identity;
    var colors = entityTypeColors(type);
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
      node.data('typeFill', colors.fill);
      node.data('typeBorder', colors.labelColor);
      node.toggleClass('type-colored', entityTypeColoringEnabled);
    } else {
      node = cy.add({
        group: 'nodes',
        data: {
          id: identity,
          label: label,
          name: name,
          type: type,
          placeholder: false,
          typeFill: colors.fill,
          typeBorder: colors.labelColor
        }
      });
      node.toggleClass('type-colored', entityTypeColoringEnabled);
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

  function retypeEntity(previousIdentity, identity, name, type) {
    if (!cy || !previousIdentity || !identity || previousIdentity === identity) {
      if (identity) {
        addEntity(identity, name, type);
      }
      return;
    }
    var oldNode = cy.getElementById(previousIdentity);
    var position = oldNode && oldNode.length > 0 ? oldNode.position() : null;
    var parent = oldNode && oldNode.length > 0 ? oldNode.parent().id() : null;
    var incidentEdges = [];
    if (oldNode && oldNode.length > 0) {
      oldNode.connectedEdges().forEach(function (edge) {
        incidentEdges.push({
          source: edge.data('source') === previousIdentity ? identity : edge.data('source'),
          target: edge.data('target') === previousIdentity ? identity : edge.data('target'),
          label: edge.data('label') || ''
        });
      });
      oldNode.connectedEdges().remove();
      oldNode.remove();
    }
    addEntity(identity, name, type);
    var newNode = cy.getElementById(identity);
    if (newNode && newNode.length > 0) {
      if (position) {
        newNode.position(position);
      }
      if (parent) {
        newNode.move({ parent: parent });
      }
    }
    incidentEdges.forEach(function (edge) {
      var edgeId = edge.source + '->' + (edge.label || 'related_to') + '->' + edge.target;
      if (cy.getElementById(edgeId).length === 0) {
        cy.add({
          group: 'edges',
          data: { id: edgeId, source: edge.source, target: edge.target, label: edge.label }
        });
      }
    });
    queueLayout();
  }

  function addCommunity(communityId, summary, memberEntityIdentities) {
    if (!cy || !communityId) {
      return;
    }
    setEmptyState(null);
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

  // Toggles entity-type color-coding (spec-10-3) live across every
  // already-rendered Entity node, not just future ones — model directly on
  // `setHullsVisible` above. Community hulls are excluded: this toggle only
  // ever concerns Entity nodes, never the Community-hull coloring language.
  function setEntityTypeColoringEnabled(enabled) {
    entityTypeColoringEnabled = !!enabled;
    if (cy) {
      cy.nodes(':not(.community-hull)').toggleClass('type-colored', entityTypeColoringEnabled);
    }
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
    if (step.kind === 'SUB_QUESTION_SPAWNED' || step.kind === 'SYNTHESIS') {
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
      var fromStepId = stepNodeId(steps[i]);
      var toStepId = stepNodeId(steps[i + 1]);
      if (steps[i].kind !== 'RELATIONSHIP' && steps[i + 1].kind !== 'RELATIONSHIP' && fromStepId && toStepId) {
        findEdgesBetween(fromStepId, toStepId).forEach(function (edge) {
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

    var previousStepId = stepNodeId(previous);
    var currentStepId = stepNodeId(current);
    if (previous && current && previous.kind !== 'RELATIONSHIP' && current.kind !== 'RELATIONSHIP'
        && previousStepId && currentStepId) {
      findEdgesBetween(previousStepId, currentStepId).forEach(function (edge) {
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
    if (step.kind === 'SUB_QUESTION_SPAWNED' || step.kind === 'SYNTHESIS') {
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

  // The `cose` layout's options, shared by `queueLayout` (the real,
  // animated, debounced layout every mutation queues) and
  // `runLayoutSynchronouslyForTest` (test-support only, see below) — kept as
  // one function so the two never drift apart.
  //
  // nestingFactor/componentSpacing are tuned beyond cose's generic defaults
  // for this app's typical graph shape (many small Communities expressed as
  // compound/parent nodes, plus isolated/disconnected entities) — see
  // spec-11-5 (GitHub #34). Both are lowered from cose's own defaults
  // (nestingFactor 1.2, componentSpacing 40): empirically (rendered, not
  // just reasoned about — see spec-11-5's own Boundaries & Constraints),
  // *raising* nestingFactor stretches every cross-Community edge's ideal
  // length (cose multiplies it by nestingFactor for edges that cross a
  // compound boundary), pushing connected Communities and their members
  // further apart, not closer — so it's lowered instead to pull them in.
  // Lowering componentSpacing shrinks the extra gap cose's own post-layout
  // packing step inserts between disconnected components (isolated
  // entities, singleton/disconnected Communities), so those don't drift as
  // far from the rest of the graph. Neither fit/padding/animate/
  // animationDuration nor the compound-node parenting above are touched.
  var TUNED_NESTING_FACTOR = 0.3;
  var TUNED_COMPONENT_SPACING = 8;

  function cyLayoutOptions(extra) {
    return Object.assign({
      name: 'cose',
      fit: true,
      padding: 32,
      randomize: false,
      nestingFactor: TUNED_NESTING_FACTOR,
      componentSpacing: TUNED_COMPONENT_SPACING
    }, extra || {});
  }

  // Gap (model px) left between two packed islands' bounding boxes.
  var PACK_GAP = 28;

  // `cose` only arranges nodes *within* a connected piece of the graph; how
  // far apart it leaves disconnected pieces (a Community with no edge to any
  // other, a lone Entity) is down to its random annealing, and on a typical,
  // edge-sparse corpus that scattered Communities across far-flung corners of
  // the canvas. So `cose` now only shapes each island, and this pass packs
  // the islands next to each other, row by row (tallest first), in a block
  // roughly matching the canvas's aspect ratio. An island is every Entity
  // linked by an edge or a shared Community, so a Community and everything
  // connected to it always move as one unit and keep their internal shape.
  // Returns the packed position of every entity node, keyed by id.
  function packedIslandPositions() {
    var entities = cy.nodes(':childless').not('.community-hull');
    var root = {};
    function find(id) {
      while (root[id] !== id) {
        root[id] = root[root[id]];
        id = root[id];
      }
      return id;
    }
    function union(a, b) {
      if (root[a] !== undefined && root[b] !== undefined) {
        root[find(a)] = find(b);
      }
    }
    entities.forEach(function (node) {
      root[node.id()] = node.id();
    });
    entities.forEach(function (node) {
      var hull = node.parent();
      if (hull.length > 0) {
        union(node.id(), hull.children()[0].id());
      }
    });
    cy.edges().forEach(function (edge) {
      union(edge.source().id(), edge.target().id());
    });

    var islandsByRoot = {};
    entities.forEach(function (node) {
      var key = find(node.id());
      islandsByRoot[key] = islandsByRoot[key] || cy.collection();
      islandsByRoot[key] = islandsByRoot[key].union(node).union(node.parent());
    });

    var islands = Object.keys(islandsByRoot).map(function (key) {
      // The rendered box (labels and hull padding included), so packed
      // hulls never overlap.
      var box = islandsByRoot[key].boundingBox();
      return { eles: islandsByRoot[key], box: box };
    });
    islands.sort(function (a, b) {
      return (b.box.h - a.box.h) || (b.box.w - a.box.w);
    });

    var totalArea = 0;
    var widest = 0;
    islands.forEach(function (island) {
      totalArea += (island.box.w + PACK_GAP) * (island.box.h + PACK_GAP);
      widest = Math.max(widest, island.box.w);
    });
    var aspect = cy.width() / Math.max(1, cy.height());
    var rowWidth = Math.max(widest, Math.sqrt(totalArea * aspect));

    var positions = {};
    var x = 0;
    var y = 0;
    var rowHeight = 0;
    islands.forEach(function (island) {
      if (x > 0 && x + island.box.w > rowWidth) {
        x = 0;
        y += rowHeight + PACK_GAP;
        rowHeight = 0;
      }
      var dx = x - island.box.x1;
      var dy = y - island.box.y1;
      island.eles.filter(':childless').forEach(function (node) {
        var p = node.position();
        positions[node.id()] = { x: p.x + dx, y: p.y + dy };
      });
      x += island.box.w + PACK_GAP;
      rowHeight = Math.max(rowHeight, island.box.h);
    });
    return positions;
  }

  var LAYOUT_PADDING = 32;
  var LAYOUT_ANIMATION_MS = 400;
  var activeLayout = null;

  // The zoom/pan that fits a model-space bounding box into the viewport —
  // what `cy.fit` would do, but for positions the nodes have not reached yet.
  function viewportFitting(box) {
    var zoom = Math.min(
      (cy.width() - 2 * LAYOUT_PADDING) / Math.max(1, box.w),
      (cy.height() - 2 * LAYOUT_PADDING) / Math.max(1, box.h));
    zoom = Math.max(cy.minZoom(), Math.min(cy.maxZoom(), zoom));
    return {
      zoom: zoom,
      pan: {
        x: (cy.width() - zoom * (box.x1 + box.x2)) / 2,
        y: (cy.height() - zoom * (box.y1 + box.y2)) / 2
      }
    };
  }

  // Runs `cose` (synchronously, off-screen: positions are snapshotted and
  // restored around it), packs its islands, then moves the real nodes to the
  // packed positions and fits the view to them — animated for the app,
  // instant for tests. Returns the final `preset` layout for the caller to
  // `run()` (after hooking its `layoutstop`, if needed).
  function runPackedLayout(animate) {
    // A corpus streams in many mutations, each queueing a layout: settle the
    // previous one first, or its still-running animation would keep pulling
    // nodes towards stale positions on top of this one's.
    if (activeLayout) {
      activeLayout.stop();
      activeLayout = null;
    }
    cy.stop(true);
    var entities = cy.nodes(':childless');
    entities.stop(true);
    var before = {};
    entities.forEach(function (node) {
      var p = node.position();
      before[node.id()] = { x: p.x, y: p.y };
    });
    cy.layout(cyLayoutOptions({ fit: false, animate: false })).run();
    var packed = packedIslandPositions();
    entities.forEach(function (node) {
      if (packed[node.id()]) {
        node.position(packed[node.id()]);
      }
    });
    // Measured with the nodes already in place, so hull padding and labels
    // count towards the fit.
    var target = viewportFitting(cy.elements().boundingBox());
    if (animate) {
      entities.forEach(function (node) {
        node.position(before[node.id()]);
      });
      cy.animate(target, { duration: LAYOUT_ANIMATION_MS });
    } else {
      cy.viewport(target);
    }
    var layout = entities.layout({
      name: 'preset',
      positions: function (node) {
        return packed[node.id()] || node.position();
      },
      fit: false,
      animate: animate,
      animationDuration: LAYOUT_ANIMATION_MS
    });
    activeLayout = layout;
    layout.one('layoutstop', function () {
      if (activeLayout === layout) {
        activeLayout = null;
      }
    });
    return layout;
  }

  // Tracks whether the debounced, animated `cose` layout `queueLayout`
  // triggers is still actually running (as opposed to merely queued) — a
  // corpus streams in many entity/relationship/community SSE events in
  // quick succession, each re-queueing this layout, so "Ready" firing does
  // not by itself mean the canvas has stopped moving. Test-support only
  // (`isLayoutActive` below); the app itself never reads this.
  var layoutRunning = false;

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
      layoutRunning = true;
      var layout = runPackedLayout(true);
      layout.one('layoutstop', function () {
        layoutRunning = false;
      });
      layout.run();
    };
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(runLayout);
    } else {
      setTimeout(runLayout, 0);
    }
  }

  // Test-support only: whether `queueLayout`'s debounced/animated layout is
  // currently queued (waiting for its next animation frame) or actually
  // running its `cose` animation — used to let a Playwright test wait
  // deterministically for the canvas to stop moving on its own, instead of
  // a fixed sleep guessing how long a corpus's own burst of SSE-triggered
  // layouts takes to settle.
  function isLayoutActive() {
    return layoutQueued || layoutRunning;
  }

  // Test-support only (spec-11-5/GraphLayoutCommunitySpacingUiTest): runs the
  // exact same tuned `cose` layout again, synchronously and unanimated, so a
  // test can sample several independent layouts of the same already-loaded
  // graph. `cose` is a stochastic simulated-annealing layout (random
  // per-iteration perturbation, cooling over a fixed iteration count) — a
  // single run's positions are one sample of a distribution, not a fixed
  // point, so a spacing assertion against only one run would be at the mercy
  // of that run's own luck. Bypasses `queueLayout`'s debounce/animation
  // entirely; does not affect the app's own real (animated, debounced)
  // layout path.
  function runLayoutSynchronouslyForTest() {
    if (!cy) {
      return false;
    }
    runPackedLayout(false).run();
    return true;
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

  // Test-support only: the raw communityId (no `community::` prefix) the
  // given Entity is currently parented under, i.e. which rendered hull it
  // belongs to — reads Cytoscape's own compound-node structure directly
  // rather than depending on any backend endpoint. Returns null if the
  // Entity isn't rendered or isn't inside a community hull.
  function communityIdForEntity(identity) {
    if (!cy || !identity) {
      return null;
    }
    var node = cy.getElementById(identity);
    if (!node || node.length === 0) {
      return null;
    }
    var parent = node.parent();
    if (!parent || parent.length === 0) {
      return null;
    }
    return communityIdFromParentId(parent.id());
  }

  // Test-support only: the actual rendered fill/border color Cytoscape's
  // style cascade resolves for a given Entity node — reading the resolved
  // style (not re-deriving "what color should this be" from data/classes)
  // is what actually determines what a viewer sees on screen, exactly like
  // `communityHullOpacity` above.
  function entityNodeFillColor(identity) {
    if (!cy || !identity) {
      return null;
    }
    var node = cy.getElementById(identity);
    if (!node || node.length === 0) {
      return null;
    }
    return node.style('background-color');
  }

  function entityNodeBorderColor(identity) {
    if (!cy || !identity) {
      return null;
    }
    var node = cy.getElementById(identity);
    if (!node || node.length === 0) {
      return null;
    }
    return node.style('border-color');
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

  // Test-support only (spec-11-1/MainScreenLayoutUiTest): exposes
  // Cytoscape's own cached container dimensions so a Playwright test can
  // assert `cy.resize()` actually ran after a viewport resize, without
  // depending on a still-animating force-directed layout's node positions.
  // Returns null before `init()` has created `cy`.
  function dimensions() {
    if (!cy) {
      return null;
    }
    return { width: cy.width(), height: cy.height() };
  }

  // Test-support only (spec-11-6/ZoomControlsUiTest): an independently-
  // computed `cy.fit()` baseline, so a test can assert `fitToView()`'s
  // result actually converges to the graph's real fitted extent rather than
  // merely differing from whatever zoom level preceded it. `cy.fit()` is
  // idempotent against an already-fitted viewport (fitting the same
  // elements/padding again lands on the same zoom), so calling it here has
  // no observable side effect when the viewport is already fitted.
  // Returns null before `init()` has created `cy`.
  function fitZoomForTest() {
    if (!cy) {
      return null;
    }
    cy.fit();
    return cy.zoom();
  }

  // Test-support only (spec-11-1/MainScreenLayoutUiTest): exposes Cytoscape's
  // own current zoom/pan so a test can assert the debounced `cy.fit()` after
  // a resize actually re-centered/re-zoomed the viewport — `dimensions()`
  // above only proves `cy.resize()` ran, not that `cy.fit()` did anything.
  // Returns null before `init()` has created `cy`.
  function viewState() {
    if (!cy) {
      return null;
    }
    return { zoom: cy.zoom(), pan: cy.pan() };
  }

  // Test-support only (spec-11-5/GraphLayoutCommunitySpacingUiTest): summarizes
  // the rendered layout's actual node spacing so a Playwright test can assert
  // on it directly, rather than re-deriving positions from raw
  // `cy.getElementById(id).position()` calls that would be brittle to change
  // and duplicate this same math per-test. All returned distances are in the
  // same rendered-position units Cytoscape itself uses (pre-zoom model
  // coordinates), so ratios between them are meaningful regardless of the
  // viewport's current zoom/pan (set by `fit`/`padding`).
  //
  // - `medianEdgeLength`: the median straight-line distance between the two
  //   endpoints of every rendered edge — this graph's own "typical" spacing
  //   unit, used as the yardstick every other distance below is judged
  //   against (a fixed pixel bound would be meaningless across corpora of
  //   different sizes).
  // - `communities`: for every rendered Community hull, the bounding-box
  //   diagonal of just its member (non-hull) nodes' positions — i.e. how far
  //   apart this Community's own members actually ended up, independent of
  //   the hull's own drawn padding.
  // - `maxNearestNeighborGap`: the largest, over every childless node, of
  //   that node's distance to its single closest other node — the metric
  //   that actually catches an isolated/singleton node (or a whole small
  //   community) drifting off to an outlier distance from the rest of the
  //   graph, since such a node's nearest neighbor would necessarily be far.
  function layoutSpacingMetrics() {
    if (!cy) {
      return null;
    }
    // `:childless` alone would also match an empty Community hull (a
    // compound node with zero children), which isn't an entity and would
    // pollute the nearest-neighbor gap measurement below — excluded here.
    var nodes = cy.nodes(':childless').not('.community-hull');
    if (nodes.length === 0) {
      return null;
    }

    function distance(a, b) {
      var dx = a.x - b.x;
      var dy = a.y - b.y;
      return Math.sqrt(dx * dx + dy * dy);
    }

    var edgeLengths = cy.edges().map(function (edge) {
      return distance(edge.source().position(), edge.target().position());
    });
    edgeLengths.sort(function (a, b) { return a - b; });
    var medianEdgeLength;
    if (edgeLengths.length === 0) {
      // Explicit `null` (not e.g. 0/NaN) so a caller dividing by this can
      // check for the no-edges case first rather than silently computing
      // a meaningless ratio.
      medianEdgeLength = null;
    } else if (edgeLengths.length % 2 === 1) {
      medianEdgeLength = edgeLengths[Math.floor(edgeLengths.length / 2)];
    } else {
      var mid = edgeLengths.length / 2;
      medianEdgeLength = (edgeLengths[mid - 1] + edgeLengths[mid]) / 2;
    }

    var positions = nodes.map(function (node) { return node.position(); });
    var maxNearestNeighborGap = 0;
    for (var i = 0; i < positions.length; i += 1) {
      if (positions.length < 2) {
        break;
      }
      var nearest = null;
      for (var j = 0; j < positions.length; j += 1) {
        if (i === j) {
          continue;
        }
        var d = distance(positions[i], positions[j]);
        if (nearest === null || d < nearest) {
          nearest = d;
        }
      }
      if (nearest !== null && nearest > maxNearestNeighborGap) {
        maxNearestNeighborGap = nearest;
      }
    }

    var communities = {};
    cy.nodes('.community-hull').forEach(function (hull) {
      var members = hull.children();
      if (members.length === 0) {
        return;
      }
      var memberPositions = members.map(function (member) { return member.position(); });
      var minX = Math.min.apply(null, memberPositions.map(function (p) { return p.x; }));
      var maxX = Math.max.apply(null, memberPositions.map(function (p) { return p.x; }));
      var minY = Math.min.apply(null, memberPositions.map(function (p) { return p.y; }));
      var maxY = Math.max.apply(null, memberPositions.map(function (p) { return p.y; }));
      var communityId = communityIdFromParentId(hull.id());
      communities[communityId] = {
        memberCount: members.length,
        diagonal: distance({ x: minX, y: minY }, { x: maxX, y: maxY })
      };
    });

    return {
      medianEdgeLength: medianEdgeLength,
      maxNearestNeighborGap: maxNearestNeighborGap,
      communities: communities
    };
  }

  window.GraphCanvas = {
    init: init,
    addEntity: addEntity,
    retypeEntity: retypeEntity,
    addRelationship: addRelationship,
    addCommunity: addCommunity,
    setHullsVisible: setHullsVisible,
    setEntityTypeColoringEnabled: setEntityTypeColoringEnabled,
    entityTypeColors: entityTypeColors,
    highlightStep: highlightStep,
    clearStepHighlights: clearStepHighlights,
    onNodeTap: onNodeTap,
    onCommunityTap: onCommunityTap,
    onBackgroundTap: onBackgroundTap,
    focusCommunity: focusCommunity,
    searchEntities: searchEntities,
    focusEntity: focusEntity,
    zoomIn: zoomIn,
    zoomOut: zoomOut,
    fitToView: fitToView,
    simulateTap: simulateTap,
    communityHullOpacity: communityHullOpacity,
    communityIdForEntity: communityIdForEntity,
    elementHasClass: elementHasClass,
    entityNodeFillColor: entityNodeFillColor,
    entityNodeBorderColor: entityNodeBorderColor,
    dimensions: dimensions,
    viewState: viewState,
    fitZoomForTest: fitZoomForTest,
    isLayoutActive: isLayoutActive,
    layoutSpacingMetrics: layoutSpacingMetrics,
    runLayoutSynchronouslyForTest: runLayoutSynchronouslyForTest
  };
})();
