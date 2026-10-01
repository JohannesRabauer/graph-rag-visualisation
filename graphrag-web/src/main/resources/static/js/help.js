/*
 * Contextual help pane. Every "?" button carries data-help="<topic>"; a click
 * fetches /help/<topic>.html (a static file under static/help/) into the
 * docked #help-pane. Each article has a generic layer (static prose + SVG)
 * and, for the three search modes, a live "in your data" layer drawn from the
 * last answer's retrieval trace (GET /api/traces/{traceId}).
 *
 * upload.js announces state through two document events (it exposes no
 * getters): 'graphrag:answer' {mode, traceId, stepCount, question, corpusId,
 * answer} and 'graphrag:corpus' {corpusId, offline}.
 */
(function () {
  'use strict';

  var SVG_NS = 'http://www.w3.org/2000/svg';

  var pane = document.getElementById('help-pane');
  var content = document.getElementById('help-pane-content');
  var closeButton = document.getElementById('help-pane-close');
  if (!pane || !content) {
    return;
  }

  var openTopic = null;
  var invoker = null;
  var loadSeq = 0;
  var liveSeq = 0;
  var lastAnswer = null;
  var offline = false;
  var corpusId = null;

  var MODE_NAMES = { LOCAL: 'Local', GLOBAL: 'Global', DRIFT: 'Drift', VECTOR: 'Vector' };

  function isOpen() {
    return !pane.hidden;
  }

  function open(topic, button) {
    if (!topic) {
      return;
    }
    // Links inside the pane swap content but keep the original "?" as the
    // focus-return target.
    if (button && !pane.contains(button)) {
      invoker = button;
    }
    openTopic = topic;
    pane.hidden = false;
    pane.dataset.topic = topic;
    var seq = ++loadSeq;

    fetch('/help/' + encodeURIComponent(topic) + '.html')
      .then(function (response) {
        if (!response.ok) {
          throw new Error('help fetch failed: ' + response.status);
        }
        return response.text();
      })
      .then(function (html) {
        if (seq !== loadSeq) {
          return;
        }
        content.innerHTML = html;
        content.scrollTop = 0;
        renderLive();
      })
      .catch(function () {
        if (seq !== loadSeq) {
          return;
        }
        content.textContent = '';
        var p = document.createElement('p');
        p.className = 'help-unavailable';
        p.textContent = 'Help unavailable for this topic right now.';
        content.appendChild(p);
      });

    // Focus the pane itself so keyboard users land in the new content and
    // Esc works from a predictable place.
    try {
      pane.focus({ preventScroll: true });
    } catch (e) {
      // ignore
    }
  }

  function close() {
    if (!isOpen()) {
      return;
    }
    pane.hidden = true;
    openTopic = null;
    loadSeq++;
    liveSeq++;
    var target = invoker;
    invoker = null;
    if (target && document.body.contains(target) && !target.hidden && target.offsetParent !== null) {
      target.focus();
      return;
    }
    var chat = document.getElementById('chat-input');
    if (chat && !chat.disabled && chat.offsetParent !== null) {
      chat.focus();
    }
  }

  document.addEventListener('click', function (event) {
    var button = event.target && event.target.closest ? event.target.closest('[data-help]') : null;
    if (!button) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    open(button.getAttribute('data-help'), button);
  }, true);

  if (closeButton) {
    closeButton.addEventListener('click', close);
  }

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && isOpen() && !event.defaultPrevented) {
      var t = event.target;
      if (t && t.closest && t.closest('#canvas-settings-popover, #corpus-history-popover, #replay-scrubber, #entity-detail-panel')) {
        return;
      }
      close();
    }
  });

  document.addEventListener('graphrag:answer', function (event) {
    var detail = event.detail || {};
    if (detail.mode !== 'LOCAL' && detail.mode !== 'GLOBAL' && detail.mode !== 'DRIFT') {
      return;
    }
    lastAnswer = detail;
    renderLive();
  });

  document.addEventListener('graphrag:corpus', function (event) {
    var detail = event.detail || {};
    var changed = detail.corpusId !== corpusId || !!detail.offline !== offline;
    corpusId = detail.corpusId || null;
    offline = !!detail.offline;
    if (changed) {
      lastAnswer = null;
    }
    renderLive();
  });

  // ---- live layer ----

  function liveMount() {
    return content.querySelector('[data-live]');
  }

  function liveMode() {
    var article = content.querySelector('[data-live-mode]');
    return article ? article.getAttribute('data-live-mode') : null;
  }

  function setLiveText(mount, text, cls) {
    mount.textContent = '';
    var p = document.createElement('p');
    p.className = 'help-live-note' + (cls ? ' ' + cls : '');
    p.textContent = text;
    mount.appendChild(p);
  }

  function renderLive() {
    var mount = liveMount();
    var mode = liveMode();
    liveSeq++;
    if (!mount || !mode) {
      return;
    }
    var seq = liveSeq;
    mount.setAttribute('data-live-state', 'loading');

    if (offline) {
      mount.setAttribute('data-live-state', 'offline');
      setLiveText(mount, 'This is the offline demo. It has no answers to trace: the question box is '
          + 'disabled and questions are rejected. Load a live corpus and ask a question to see this in your data.');
      return;
    }
    if (!lastAnswer) {
      mount.setAttribute('data-live-state', 'empty');
      setLiveText(mount, 'Ask a question to see this in your data');
      return;
    }
    if (lastAnswer.mode !== mode) {
      mount.setAttribute('data-live-state', 'other-mode');
      setLiveText(mount, 'Your last answer used ' + (MODE_NAMES[lastAnswer.mode] || lastAnswer.mode)
          + ' Search. Ask a question in ' + MODE_NAMES[mode] + ' Search to see this in your data.');
      return;
    }
    if (!lastAnswer.traceId) {
      mount.setAttribute('data-live-state', 'no-match');
      setLiveText(mount, 'This mode found nothing to trace.');
      return;
    }

    var answer = lastAnswer;
    fetch('/api/traces/' + encodeURIComponent(answer.traceId))
      .then(function (response) {
        if (response.status === 404) {
          return { steps: [] };
        }
        if (!response.ok) {
          throw new Error('trace fetch failed: ' + response.status);
        }
        return response.json();
      })
      .then(function (body) {
        if (seq !== liveSeq) {
          return;
        }
        var steps = body && Array.isArray(body.steps) ? body.steps : [];
        if (steps.length === 0) {
          mount.setAttribute('data-live-state', 'no-match');
          setLiveText(mount, 'This mode found nothing to trace.');
          return;
        }
        mount.textContent = '';
        mount.setAttribute('data-live-state', 'ready');
        if (mode === 'LOCAL') {
          renderLocal(mount, steps);
        } else if (mode === 'GLOBAL') {
          renderGlobal(mount, steps, answer);
        } else {
          renderDrift(mount, steps);
        }
      })
      .catch(function () {
        if (seq !== liveSeq) {
          return;
        }
        mount.setAttribute('data-live-state', 'error');
        setLiveText(mount, 'Could not load the trace.');
      });
  }

  // ---- SVG helpers (vertical layouts sized for the narrow pane) ----

  var SVG_W = 400;

  function svgEl(name, attrs) {
    var el = document.createElementNS(SVG_NS, name);
    Object.keys(attrs || {}).forEach(function (key) {
      el.setAttribute(key, attrs[key]);
    });
    return el;
  }

  function clip(text, max) {
    var s = String(text == null ? '' : text);
    return s.length > max ? s.slice(0, max - 1) + '…' : s;
  }

  function newSvg(height, label) {
    var svg = svgEl('svg', {
      viewBox: '0 0 ' + SVG_W + ' ' + height,
      role: 'img',
      'aria-label': label,
      'class': 'help-svg help-live-svg'
    });
    svg.appendChild(svgEl('defs', {})).innerHTML =
        '<marker id="help-live-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
        + 'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" class="hs-arrow-head"/></marker>';
    return svg;
  }

  function box(svg, x, y, w, h, text, cls, title) {
    var g = svgEl('g', {});
    if (title) {
      g.appendChild(svgEl('title', {})).textContent = title;
    }
    g.appendChild(svgEl('rect', { x: x, y: y, width: w, height: h, rx: 8, 'class': 'hs-box ' + (cls || '') }));
    var t = svgEl('text', { x: x + w / 2, y: y + h / 2 + 5, 'text-anchor': 'middle', 'class': 'hs-text' });
    t.textContent = text;
    g.appendChild(t);
    svg.appendChild(g);
  }

  function arrow(svg, x1, y1, x2, y2) {
    svg.appendChild(svgEl('line', {
      x1: x1, y1: y1, x2: x2, y2: y2, 'class': 'hs-line', 'marker-end': 'url(#help-live-arrow)'
    }));
  }

  function label(svg, x, y, text, anchor) {
    var t = svgEl('text', { x: x, y: y, 'text-anchor': anchor || 'start', 'class': 'hs-text hs-text--small' });
    t.textContent = text;
    svg.appendChild(t);
  }

  function captionList(mount, items) {
    var ul = document.createElement('ul');
    ul.className = 'help-live-captions';
    items.forEach(function (item) {
      var li = document.createElement('li');
      var strong = document.createElement('strong');
      strong.textContent = item.head + ' ';
      li.appendChild(strong);
      li.appendChild(document.createTextNode(item.text));
      ul.appendChild(li);
    });
    mount.appendChild(ul);
  }

  function relationshipType(step) {
    var parts = String(step.identifier || '').split('->');
    return parts.length === 3 ? parts[1].replace(/_/g, ' ') : 'related to';
  }

  // Local: seed entity -> relationship -> other entity. With semantic seed
  // matching the trace opens with up to three seed ENTITY steps; the first is
  // the seed and the other entity is the one after the relationship.
  function localChain(steps) {
    var seed = null;
    var rel = null;
    var other = null;
    steps.forEach(function (step) {
      if (step.kind === 'ENTITY') {
        if (!seed) {
          seed = step;
        } else if (rel && !other) {
          other = step;
        }
      } else if (step.kind === 'RELATIONSHIP' && !rel) {
        rel = step;
      }
    });
    return { seed: seed, rel: rel, other: other };
  }

  function renderLocal(mount, steps) {
    var chain = localChain(steps);
    if (!chain.seed) {
      setLiveText(mount, 'This mode found nothing to trace.');
      mount.setAttribute('data-live-state', 'no-match');
      return;
    }
    var height = chain.rel ? (chain.other ? 210 : 160) : 70;
    var svg = newSvg(height, 'Path this Local answer walked: seed entity, relationship, other entity');
    box(svg, 20, 8, 360, 46, clip(chain.seed.label || chain.seed.identifier, 34), 'hs-box--local', chain.seed.label);
    var items = [{ head: 'Seed entity:', text: chain.seed.label || chain.seed.identifier }];
    if (chain.rel) {
      arrow(svg, 200, 54, 200, 96);
      label(svg, 212, 82, clip(relationshipType(chain.rel), 30));
      box(svg, 20, 100, 360, 46, clip(chain.rel.label || chain.rel.identifier, 34), 'hs-box--edge', chain.rel.label);
      if (chain.other) {
        arrow(svg, 200, 146, 200, 160);
        box(svg, 20, 160, 360, 46, clip(chain.other.label || chain.other.identifier, 34), 'hs-box--local',
            chain.other.label);
      }
      items.push({ head: 'Relationship:', text: chain.rel.label || chain.rel.identifier });
      if (chain.other) {
        items.push({ head: 'Other entity:', text: chain.other.label || chain.other.identifier });
      }
    } else {
      items.push({ head: 'No relationship:', text: 'the seed matched, but none of its relationships matched the question.' });
    }
    mount.appendChild(svg);
    captionList(mount, items);
  }

  // Global: all community steps, best marked (the best summary is quoted in
  // the answer text, since the trace itself carries no score).
  function renderGlobal(mount, steps, answer) {
    var communities = steps.filter(function (s) {
      return s.kind === 'COMMUNITY';
    });
    if (communities.length === 0) {
      mount.setAttribute('data-live-state', 'no-match');
      setLiveText(mount, 'This mode found nothing to trace.');
      return;
    }
    var answerText = String((answer && answer.answer) || '');
    var noAnswer = !!(answer && answer.noAnswer);
    var best = null;
    var bestHow = 'inferred from the answer text';
    // Story 15.3: a synthesized trace (it reads TEXT_UNIT passages) is not
    // matched against the answer text; the first community, the closest
    // match, is marked.
    var synthesized = steps.some(function (s) {
      return s.kind === 'TEXT_UNIT';
    });
    if (synthesized) {
      best = communities[0];
      bestHow = 'closest match, read first';
    } else {
      communities.forEach(function (c) {
        if (c.label && answerText.indexOf(c.label) !== -1 && (!best || c.label.length > best.label.length)) {
          best = c;
        }
      });
    }
    var ordered = best ? [best].concat(communities.filter(function (c) {
      return c !== best;
    })) : communities;
    var shown = ordered.slice(0, 8);
    var rowH = 46;
    var gap = 10;
    var height = shown.length * (rowH + gap) + 4;
    var svg = newSvg(height, 'All communities Global Search read, best match marked');
    shown.forEach(function (c, i) {
      var y = 2 + i * (rowH + gap);
      var isBest = best === c;
      box(svg, 20, y, 360, rowH,
          (isBest ? '★ ' : '') + clip(c.identifier, 12) + ': ' + clip(c.label, 22),
          isBest ? 'hs-box--global hs-box--best' : 'hs-box--muted',
          isBest ? 'Best match (' + bestHow + '): ' + c.label : c.label);
    });
    mount.appendChild(svg);
    var items = shown.map(function (c) {
      return { head: (best === c ? 'Best match (' + bestHow + '), ' : '') + c.identifier + ':', text: c.label || '' };
    });
    if (communities.length > shown.length) {
      items.push({ head: 'And', text: (communities.length - shown.length) + ' more communities were also read.' });
    }
    if (synthesized && noAnswer) {
      items.unshift({ head: 'Note:', text: 'these communities and their passages were read, but they did not answer the question, so no answer was written; the first community read is marked as the closest match.' });
    } else if (synthesized) {
      items.unshift({ head: 'Note:', text: 'the answer was written from these communities and their passages; the first community read is marked as the closest match.' });
    } else if (best) {
      items.unshift({ head: 'Note:', text: 'the trace carries no scores, so the best match is inferred from which summary the answer quotes.' });
    } else {
      items.unshift({ head: 'No clear winner:', text: 'no summary could be identified in the answer text, so none is marked.' });
    }
    captionList(mount, items);
  }

  // Drift: communities -> sub-questions -> local steps -> synthesis. Grouping
  // mirrors drift-tree.js (a SUB_QUESTION_SPAWNED opens a branch, the first
  // SYNTHESIS closes them).
  function renderDrift(mount, steps) {
    var communities = [];
    var branches = [];
    var synthesis = null;
    var current = null;
    steps.forEach(function (step) {
      if (step.kind === 'COMMUNITY') {
        communities.push(step);
      } else if (step.kind === 'SUB_QUESTION_SPAWNED') {
        current = { spawn: step, steps: [] };
        branches.push(current);
      } else if (step.kind === 'SYNTHESIS') {
        if (!synthesis) {
          synthesis = step;
        }
        current = null;
      } else if (current && !synthesis) {
        current.steps.push(step);
      }
    });
    if (communities.length === 0 && branches.length === 0) {
      mount.setAttribute('data-live-state', 'no-match');
      setLiveText(mount, 'This mode found nothing to trace.');
      return;
    }

    var spawnedIds = {};
    branches.forEach(function (b) {
      spawnedIds[b.spawn.identifier] = true;
    });
    var orderedCommunities = communities.filter(function (c) {
      return spawnedIds[c.identifier];
    }).concat(communities.filter(function (c) {
      return !spawnedIds[c.identifier];
    }));
    var shownCommunities = orderedCommunities.slice(0, 5);
    var shownBranches = branches.slice(0, 3);
    var rowH = 40;
    var y;
    var height = 30 + shownCommunities.length * (rowH + 8)
        + (shownBranches.length ? 30 + shownBranches.length * (rowH * 2 + 26) : 0)
        + (synthesis ? 60 : 0) + 10;
    var svg = newSvg(height, 'Path this Drift answer took: communities, sub-questions, local steps, synthesis');

    label(svg, 20, 20, 'Communities read', 'start');
    y = 30;
    shownCommunities.forEach(function (c) {
      var spawned = !!spawnedIds[c.identifier];
      box(svg, 20, y, 360, rowH, clip(c.identifier, 12) + ': ' + clip(c.label, 24),
          spawned ? 'hs-box--drift hs-box--best' : 'hs-box--muted', c.label);
      y += rowH + 8;
    });

    var items = [];
    orderedCommunities.forEach(function (c) {
      var spawned = !!spawnedIds[c.identifier];
      items.push({ head: c.identifier + (spawned ? ' (spawned a sub-question):' : ':'), text: c.label || '' });
    });

    shownBranches.forEach(function (branch, index) {
      if (index === 0) {
        label(svg, 20, y + 18, 'Sub-questions and local steps', 'start');
        y += 28;
      }
      arrow(svg, 200, y - 6, 200, y + 2);
      box(svg, 20, y, 360, rowH, clip(branch.spawn.label, 34), 'hs-box--drift', branch.spawn.label);
      y += rowH + 8;
      var chain = localChain(branch.steps);
      var text = chain.seed
          ? clip(chain.seed.label, 12) + ' → ' + (chain.rel ? clip(relationshipType(chain.rel), 12) : '-')
              + ' → ' + (chain.other ? clip(chain.other.label, 12) : '-')
          : 'no local match';
      box(svg, 40, y, 340, rowH, text, chain.seed ? 'hs-box--local' : 'hs-box--muted', text);
      y += rowH + 10;
      items.push({ head: 'Sub-question ' + (index + 1) + ':', text: branch.spawn.label || '' });
      items.push({
        head: 'Local steps ' + (index + 1) + ':',
        text: chain.seed
            ? (chain.seed.label || '') + (chain.rel ? ' — ' + (chain.rel.label || '') : '')
                + (chain.other ? ' — ' + (chain.other.label || '') : '')
            : 'the sub-question found no local match.'
      });
    });
    if (branches.length > shownBranches.length) {
      items.push({ head: 'And', text: (branches.length - shownBranches.length) + ' more sub-questions ran.' });
    }
    if (synthesis) {
      arrow(svg, 200, y - 8, 200, y);
      box(svg, 20, y, 360, rowH, 'Synthesis: ' + clip(synthesis.label, 24), 'hs-box--drift hs-box--best', synthesis.label);
      items.push({ head: 'Synthesis:', text: synthesis.label || '' });
      y += rowH;
    }
    svg.setAttribute('viewBox', '0 0 ' + SVG_W + ' ' + (y + 6));
    mount.appendChild(svg);
    captionList(mount, items);
  }

  window.Help = {
    open: open,
    close: close,
    isOpen: isOpen,
    currentTopic: function () {
      return openTopic;
    }
  };
})();
