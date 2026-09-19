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
    setHullsVisible: setHullsVisible
  };
})();
