(function () {
  'use strict';

  // Cytoscape has no built-in "hull" primitive. A pale bounding shape behind a
  // Community's member nodes is approximated with a compound parent node per
  // Community: one parent styled with the Community's pale fill/label color,
  // with each member Entity node's `parent` set to it. Cytoscape auto-sizes
  // and positions the parent's background around its children as the layout
  // runs, which reads as a soft hull with no extra geometry library.
  //
  // Toggling the hull layer OFF hides the compound parent nodes' background
  // and border via the `hull-hidden` class, while child nodes stay visible
  // and still parented — so toggling back ON needs no re-fetch or replay.

  var HULL_COLOR_COUNT = 5;

  var cy = null;
  var hullsVisible = true;
  var layoutTimer = null;

  function cssVar(name, fallback) {
    try {
      var value = getComputedStyle(document.documentElement).getPropertyValue(name);
      return value && value.trim() ? value.trim() : fallback;
    } catch (e) {
      return fallback;
    }
  }

  function scheduleLayout() {
    if (!cy) {
      return;
    }
    if (layoutTimer) {
      clearTimeout(layoutTimer);
    }
    layoutTimer = setTimeout(function () {
      layoutTimer = null;
      if (!cy) {
        return;
      }
      cy.layout({ name: 'cose', animate: false, fit: true, padding: 32, randomize: false }).run();
    }, 120);
  }

  function communityColorIndex(communityId) {
    var match = /(\d+)\s*$/.exec(communityId || '');
    var n = match ? parseInt(match[1], 10) : 1;
    if (!n || isNaN(n) || n < 1) {
      n = 1;
    }
    return ((n - 1) % HULL_COLOR_COUNT) + 1;
  }

  function buildStyle() {
    var styles = [
      {
        selector: 'node.entity',
        style: {
          shape: 'round-rectangle',
          'background-color': cssVar('--node-fill', '#FFFFFF'),
          'border-color': cssVar('--node-line', '#4B5563'),
          'border-width': 1.5,
          label: 'data(label)',
          'font-family': cssVar('--font-ui', 'sans-serif'),
          'font-size': 9,
          color: cssVar('--ink-900', '#14181C'),
          'text-valign': 'center',
          'text-halign': 'center',
          padding: '6px',
          width: 'label',
          height: 'label'
        }
      },
      {
        selector: 'edge',
        style: {
          'line-color': cssVar('--node-line', '#4B5563'),
          'target-arrow-color': cssVar('--node-line', '#4B5563'),
          'target-arrow-shape': 'triangle',
          'arrow-scale': 0.7,
          'curve-style': 'bezier',
          width: 1,
          label: 'data(label)',
          'font-size': 7,
          color: cssVar('--ink-400', '#8B94A0'),
          'text-rotation': 'autorotate'
        }
      },
      {
        selector: '.hull-hidden',
        style: {
          'background-opacity': 0,
          'border-width': 0,
          label: ''
        }
      }
    ];

    for (var i = 1; i <= HULL_COLOR_COUNT; i += 1) {
      styles.push({
        selector: 'node.community-hull.community-' + i,
        style: {
          shape: 'round-rectangle',
          'background-color': cssVar('--community-' + i, '#EEEEEE'),
          'background-opacity': 0.6,
          'border-width': 1,
          'border-color': cssVar('--community-' + i + '-label', '#8B94A0'),
          color: cssVar('--community-' + i + '-label', '#8B94A0'),
          label: 'data(label)',
          'font-family': cssVar('--font-mono', 'monospace'),
          'font-size': 8,
          'text-transform': 'uppercase',
          'text-valign': 'top',
          'text-halign': 'center',
          'text-margin-y': -6,
          padding: '18px'
        }
      });
    }

    return styles;
  }

  function init(container) {
    if (typeof cytoscape === 'undefined' || !container) {
      console.warn('Cytoscape.js is unavailable; the graph canvas will stay empty.');
      cy = null;
      return;
    }

    if (cy) {
      cy.destroy();
      cy = null;
    }

    hullsVisible = true;
    cy = cytoscape({
      container: container,
      style: buildStyle(),
      layout: { name: 'preset' },
      userZoomingEnabled: false,
      userPanningEnabled: false,
      boxSelectionEnabled: false,
      autoungrabify: true,
      autounselectify: true
    });
  }

  function reset() {
    hullsVisible = true;
    if (cy) {
      cy.elements().remove();
    }
  }

  function ensureNode(identity, label) {
    if (!cy || !identity) {
      return;
    }
    if (cy.getElementById(identity).length === 0) {
      cy.add({ group: 'nodes', classes: 'entity', data: { id: identity, label: label || identity } });
    }
  }

  function addEntity(identity, name, type) {
    if (!cy || !identity) {
      return;
    }
    if (cy.getElementById(identity).length > 0) {
      return;
    }
    cy.add({
      group: 'nodes',
      classes: 'entity',
      data: { id: identity, label: name || identity, type: type || 'Unknown' }
    });
    scheduleLayout();
  }

  function addRelationship(sourceIdentity, targetIdentity, type, sourceLabel, targetLabel) {
    if (!cy || !sourceIdentity || !targetIdentity) {
      return;
    }
    ensureNode(sourceIdentity, sourceLabel);
    ensureNode(targetIdentity, targetLabel);

    var edgeId = sourceIdentity + '::' + (type || 'related_to') + '::' + targetIdentity;
    if (cy.getElementById(edgeId).length > 0) {
      return;
    }
    cy.add({
      group: 'edges',
      data: { id: edgeId, source: sourceIdentity, target: targetIdentity, label: type || '' }
    });
    scheduleLayout();
  }

  function addCommunity(communityId, memberIdentities, summary) {
    if (!cy || !communityId) {
      return;
    }

    var colorIndex = communityColorIndex(communityId);
    var existing = cy.getElementById(communityId);
    var hullNode = existing;
    var isNewHull = existing.length === 0;

    if (isNewHull) {
      var classes = 'community-hull community-' + colorIndex + (hullsVisible ? '' : ' hull-hidden');
      hullNode = cy.add({
        group: 'nodes',
        classes: classes,
        data: { id: communityId, label: communityId, summary: summary || '' }
      });
    }

    (memberIdentities || []).forEach(function (identity) {
      if (!identity) {
        return;
      }
      ensureNode(identity, identity);
      var member = cy.getElementById(identity);
      if (member && member.length > 0) {
        member.move({ parent: communityId });
      }
    });

    if (isNewHull && hullsVisible) {
      hullNode.style({ 'background-opacity': 0, 'border-width': 0 });
      hullNode.animate(
        { style: { 'background-opacity': 0.6, 'border-width': 1 } },
        { duration: 500, easing: 'ease-out' }
      );
    }

    scheduleLayout();
  }

  function setHullsVisible(visible) {
    hullsVisible = !!visible;
    if (!cy) {
      return;
    }
    cy.nodes('.community-hull').forEach(function (node) {
      node.stop(true, true);
      if (hullsVisible) {
        node.removeClass('hull-hidden');
        node.style({ 'background-opacity': 0.6, 'border-width': 1 });
      } else {
        node.addClass('hull-hidden');
      }
    });
  }

  window.GraphCanvas = {
    init: init,
    reset: reset,
    addEntity: addEntity,
    addRelationship: addRelationship,
    addCommunity: addCommunity,
    setHullsVisible: setHullsVisible
  };
})();
