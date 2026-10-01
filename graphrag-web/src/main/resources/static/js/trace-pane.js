(function () {
  'use strict';

  // Owns the Retrieval Trace pane: the right-hand pane that opens with every
  // Replay and lists all of the trace's steps, grouped into the phases the
  // search mode went through, with a plain-language reason for each step.
  // replay.js owns the scrubber bar and the current step; it calls
  // `TracePane.open(steps, context)` once a trace is loaded,
  // `TracePane.highlight(index)` on every step change and
  // `TracePane.clear()` when Replay closes. Clicking a step row asks
  // replay.js to jump there (`Replay.goTo`). The Drift tree (drift-tree.js)
  // is a section of this pane; it keeps its own show/hide toggle.

  var pane = document.getElementById('trace-pane');
  var modeEl = document.getElementById('trace-pane-mode');
  var introEl = document.getElementById('trace-pane-intro');
  var currentCard = document.getElementById('trace-current');
  var phaseEl = document.getElementById('replay-phase');
  var currentHeadEl = document.getElementById('trace-current-head');
  var currentLabelEl = document.getElementById('trace-current-label');
  var hintEl = document.getElementById('replay-hint');
  var listEl = document.getElementById('trace-step-list');
  var closeButton = document.getElementById('trace-pane-close');
  var toggleButton = document.getElementById('replay-trace-toggle');

  if (!pane || !listEl) {
    return;
  }

  var ROW_LABEL_CHARS = 90;

  var steps = [];
  var analysis = null;
  var corpusId = null;
  var isOpen = false;
  // Set when the viewer hides the pane; the replay bar's toggle flips it.
  // Reset on `clear()`, so the next Replay opens with the pane shown.
  var dismissed = false;
  // 'knowledge-graph' or 'vector-space': the canvas tab the open trace
  // belongs to. The pane only shows while that tab is the current one.
  var surface = 'knowledge-graph';
  var currentTab = 'knowledge-graph';
  var currentIndex = 0;

  var MODE_LABELS = {
    LOCAL: 'Local Search',
    GLOBAL: 'Global Search',
    DRIFT: 'DRIFT Search',
    VECTOR: 'Vector Search'
  };

  var KIND_LABELS = {
    ENTITY: 'Entity',
    RELATIONSHIP: 'Relationship',
    COMMUNITY: 'Community',
    TEXT_UNIT: 'Passage',
    SUB_QUESTION_SPAWNED: 'Sub-question',
    SYNTHESIS: 'Answer',
    VECTOR_QUERY_EMBEDDED: 'Question',
    VECTOR_CHUNK: 'Chunk'
  };

  var VERBS = {
    ENTITY: 'Matched entity',
    RELATIONSHIP: 'Traversed relationship',
    COMMUNITY: 'Examined community',
    TEXT_UNIT: 'Read passage',
    SUB_QUESTION_SPAWNED: 'Spawned sub-question',
    SYNTHESIS: 'Synthesized answer',
    VECTOR_QUERY_EMBEDDED: 'Embedded query',
    VECTOR_CHUNK: 'Retrieved chunk'
  };

  if (closeButton) {
    closeButton.addEventListener('click', function () {
      dismissed = true;
      applyVisibility();
      if (toggleButton && !toggleButton.hidden) {
        toggleButton.focus();
      }
    });
  }

  if (toggleButton) {
    toggleButton.addEventListener('click', function () {
      dismissed = !dismissed;
      applyVisibility();
    });
  }

  document.addEventListener('graphrag:canvas-tab', function (event) {
    currentTab = (event.detail && event.detail.tab) || 'knowledge-graph';
    applyVisibility();
  });

  listEl.addEventListener('click', function (event) {
    var row = event.target && event.target.closest ? event.target.closest('.trace-step') : null;
    if (!row || !window.Replay || typeof window.Replay.goTo !== 'function') {
      return;
    }
    window.Replay.goTo(Number(row.dataset.index));
  });

  // ---- public API ----

  function open(stepList, context) {
    var ctx = context || {};
    steps = Array.isArray(stepList) ? stepList : [];
    corpusId = ctx.corpusId || null;
    surface = ctx.surface === 'vector-space' ? 'vector-space' : 'knowledge-graph';
    analysis = analyze(steps, ctx.mode);
    isOpen = true;
    dismissed = false;
    currentIndex = 0;
    render(!!ctx.loadError);
    applyVisibility();
  }

  function highlight(index) {
    if (!isOpen || steps.length === 0) {
      return;
    }
    currentIndex = Math.max(0, Math.min(steps.length - 1, index));
    renderCurrent();
    var rows = listEl.querySelectorAll('.trace-step');
    for (var i = 0; i < rows.length; i += 1) {
      var isNow = i === currentIndex;
      rows[i].classList.toggle('is-current', isNow);
      rows[i].classList.toggle('is-done', i < currentIndex);
      rows[i].setAttribute('aria-current', isNow ? 'step' : 'false');
      if (isNow && rows[i].scrollIntoView && !pane.hidden) {
        try {
          rows[i].scrollIntoView({ block: 'nearest' });
        } catch (e) {
          rows[i].scrollIntoView();
        }
      }
    }
  }

  function clear() {
    isOpen = false;
    dismissed = false;
    steps = [];
    analysis = null;
    corpusId = null;
    listEl.textContent = '';
    applyVisibility();
  }

  function show() {
    if (!isOpen) {
      return;
    }
    dismissed = false;
    applyVisibility();
  }

  window.TracePane = {
    open: open,
    highlight: highlight,
    clear: clear,
    show: show,
    analyze: analyze
  };

  // ---- rendering ----

  function applyVisibility() {
    var onSurface = isOpen && currentTab === surface;
    pane.hidden = !onSurface || dismissed;
    if (toggleButton) {
      toggleButton.hidden = !onSurface;
      toggleButton.setAttribute('aria-pressed', dismissed ? 'false' : 'true');
      toggleButton.textContent = dismissed ? 'Show trace pane' : 'Hide trace pane';
    }
  }

  function render(loadError) {
    if (modeEl) {
      modeEl.textContent = analysis.modeLabel;
      modeEl.dataset.mode = analysis.mode;
    }
    if (introEl) {
      introEl.textContent = loadError
          ? 'This Retrieval Trace could not be loaded. Please try again.'
          : steps.length === 0
              ? 'Nothing was touched for this answer: the search found no entity, relationship or community'
                  + ' that matched the question, so there is nothing to replay.'
              : analysis.intro;
    }
    listEl.textContent = '';
    if (loadError || steps.length === 0) {
      if (currentCard) {
        currentCard.hidden = true;
      }
      return;
    }
    if (currentCard) {
      currentCard.hidden = false;
    }

    analysis.phases.forEach(function (phase, phaseIndex) {
      var section = document.createElement('section');
      section.className = 'trace-phase';
      section.dataset.phaseIndex = String(phaseIndex);

      var heading = document.createElement('h3');
      heading.className = 'trace-phase-title';
      heading.textContent = phase.title;
      section.appendChild(heading);

      var explanation = document.createElement('p');
      explanation.className = 'trace-phase-why';
      explanation.textContent = phase.explanation;
      section.appendChild(explanation);

      var rows = document.createElement('ol');
      rows.className = 'trace-phase-steps';
      for (var i = phase.start; i < phase.end; i += 1) {
        rows.appendChild(buildRow(i));
      }
      section.appendChild(rows);
      listEl.appendChild(section);
    });
    highlight(currentIndex);
  }

  function buildRow(index) {
    var step = steps[index];
    var item = document.createElement('li');
    var row = document.createElement('button');
    row.type = 'button';
    row.className = 'trace-step trace-step--' + String(step.kind || '').toLowerCase().replace(/_/g, '-');
    row.dataset.index = String(index);
    row.setAttribute('aria-label', 'Go to step ' + (index + 1) + ' of ' + steps.length);

    var number = document.createElement('span');
    number.className = 'trace-step-number';
    number.textContent = pad(index + 1);
    row.appendChild(number);

    var body = document.createElement('span');
    body.className = 'trace-step-body';

    var kind = document.createElement('span');
    kind.className = 'trace-step-kind';
    kind.textContent = KIND_LABELS[step.kind] || step.kind;
    body.appendChild(kind);

    var text = document.createElement('span');
    text.className = 'trace-step-label';
    text.textContent = clip(rowLabel(step), ROW_LABEL_CHARS);
    body.appendChild(text);

    // The reason for a step is shown in the current-step card above the
    // list, not repeated on the row, so the list stays scannable.
    row.title = analysis.reasons[index] || '';

    row.appendChild(body);
    item.appendChild(row);
    return item;
  }

  function renderCurrent() {
    var step = steps[currentIndex];
    if (!step) {
      return;
    }
    var phase = phaseAt(currentIndex);
    if (phaseEl) {
      var badge = phase ? analysis.modeShort + ' ' + (phase.index + 1) + '/' + analysis.phases.length
          + ' · ' + phase.title : '';
      phaseEl.textContent = badge;
      phaseEl.hidden = !badge;
    }
    if (currentHeadEl) {
      currentHeadEl.textContent = 'Step ' + (currentIndex + 1) + ' of ' + steps.length + ' — '
          + (VERBS[step.kind] || 'Touched');
    }
    if (currentLabelEl) {
      currentLabelEl.textContent = fullLabel(step);
    }
    if (hintEl) {
      hintEl.textContent = analysis.reasons[currentIndex] || '';
      hintEl.hidden = !hintEl.textContent;
    }
  }

  function phaseAt(index) {
    for (var i = 0; i < analysis.phases.length; i += 1) {
      var phase = analysis.phases[i];
      if (index >= phase.start && index < phase.end) {
        return { index: i, title: phase.title };
      }
    }
    return null;
  }

  function passageFor(step) {
    if (!step || step.kind !== 'TEXT_UNIT' || !corpusId || !window.Passages) {
      return null;
    }
    return window.Passages.cached(corpusId, step.identifier);
  }

  function passageName(step) {
    var passage = passageFor(step);
    if (!passage || !passage.documentName) {
      return null;
    }
    return typeof passage.ordinal === 'number'
        ? 'Passage ' + (passage.ordinal + 1) + ' of ' + passage.documentName
        : 'Passage of ' + passage.documentName;
  }

  function rowLabel(step) {
    if (step.kind === 'TEXT_UNIT') {
      return passageName(step) || step.label || step.identifier || '';
    }
    if (step.kind === 'VECTOR_CHUNK') {
      var score = chunkScore(step);
      return score ? 'similarity ' + score : (step.label || step.identifier || '');
    }
    return step.label || step.identifier || '';
  }

  function fullLabel(step) {
    if (step.kind === 'TEXT_UNIT') {
      var name = passageName(step);
      return (name ? name + ': ' : '') + (step.label || '');
    }
    if (step.kind === 'VECTOR_CHUNK') {
      var score = chunkScore(step);
      return (score ? 'Cosine similarity ' + score : (step.label || ''))
          + ' · chunk ' + (step.identifier || '');
    }
    return step.label || step.identifier || '';
  }

  function chunkScore(step) {
    var match = /score=([0-9.]+)/.exec(step.label || '');
    return match ? match[1] : null;
  }

  // ---- analysis: phases and per-step reasons ----

  function analyze(stepList, modeHint) {
    var safeSteps = Array.isArray(stepList) ? stepList : [];
    var mode = detectMode(safeSteps, modeHint);
    var result = {
      mode: mode,
      modeLabel: MODE_LABELS[mode] || 'Retrieval',
      modeShort: mode === 'DRIFT' ? 'Drift' : mode === 'GLOBAL' ? 'Global'
          : mode === 'VECTOR' ? 'Vector' : 'Local',
      intro: '',
      phases: [],
      reasons: safeSteps.map(function () {
        return '';
      })
    };
    if (safeSteps.length === 0) {
      return result;
    }
    if (mode === 'VECTOR') {
      analyzeVector(safeSteps, result);
    } else if (mode === 'DRIFT') {
      analyzeDrift(safeSteps, result);
    } else if (mode === 'GLOBAL') {
      analyzeGlobal(safeSteps, result);
    } else {
      analyzeLocal(safeSteps, result);
    }
    return result;
  }

  function detectMode(safeSteps, modeHint) {
    var hasSpawn = safeSteps.some(function (s) {
      return s.kind === 'SUB_QUESTION_SPAWNED';
    });
    if (safeSteps.length > 0 && safeSteps[0].kind === 'VECTOR_QUERY_EMBEDDED') {
      return 'VECTOR';
    }
    if (hasSpawn) {
      return 'DRIFT';
    }
    if (modeHint === 'LOCAL' || modeHint === 'GLOBAL' || modeHint === 'DRIFT' || modeHint === 'VECTOR') {
      return modeHint;
    }
    if (safeSteps.length > 0 && safeSteps[0].kind === 'COMMUNITY') {
      // A Drift run that spawned no branch still ends in a SYNTHESIS step;
      // Global never records one.
      return safeSteps.some(function (s) {
        return s.kind === 'SYNTHESIS';
      }) ? 'DRIFT' : 'GLOBAL';
    }
    return 'LOCAL';
  }

  function hasKind(safeSteps, kind, start, end) {
    for (var i = start; i < end; i += 1) {
      if (safeSteps[i] && safeSteps[i].kind === kind) {
        return true;
      }
    }
    return false;
  }

  function addPhase(result, title, explanation, start, end) {
    if (end > start) {
      result.phases.push({ title: title, explanation: explanation, start: start, end: end });
    }
  }

  // Local-style walk over steps[start, end): seed entities, then either one
  // relationship hop (templated answer) or the relationships and passages a
  // synthesized answer is written from. Shared with each Drift branch.
  function describeLocalWalk(safeSteps, result, start, end, options) {
    var opts = options || {};
    var seedsEnd = start;
    while (seedsEnd < end && safeSteps[seedsEnd].kind === 'ENTITY') {
      seedsEnd += 1;
    }
    var synthesized = hasKind(safeSteps, 'TEXT_UNIT', start, end);
    var relationshipsEnd = seedsEnd;
    while (relationshipsEnd < end && safeSteps[relationshipsEnd].kind === 'RELATIONSHIP') {
      relationshipsEnd += 1;
    }
    var owner = opts.owner || 'the question';

    for (var i = start; i < end; i += 1) {
      var step = safeSteps[i];
      var reason;
      if (i < seedsEnd) {
        reason = i === start
            ? 'Seed entity: of all entities in the graph, the one closest in meaning to ' + owner
                + ' (or sharing the most words with it when the corpus has no embeddings).'
                + ' The walk starts here.'
            : 'Seed entity ' + (i - start + 1) + ': also among the entities closest in meaning to '
                + owner + ', so its neighbourhood is searched too.';
      } else if (step.kind === 'RELATIONSHIP') {
        reason = synthesized
            ? 'A relationship touching a seed entity. Up to 10 of them enter the context, heaviest first'
                + ' (weight = how often the corpus states it); each is a graph fact the answer can use.'
            : 'Of all relationships touching the seed, the one whose wording best matches ' + owner
                + '. Following it is the single hop that answers.';
      } else if (step.kind === 'ENTITY') {
        reason = 'The entity at the other end of the relationship just followed; the answer names it.';
      } else if (step.kind === 'TEXT_UNIT') {
        reason = (opts.passageLead || 'A source passage that the seed entities and the collected relationships'
            + ' were extracted from.')
            + ' Passages are ranked by how many of those cite them; the top 5 are read and can be cited as [n].';
      } else {
        reason = 'Touched while answering.';
      }
      result.reasons[i] = reason;
    }

    return {
      seedsEnd: seedsEnd,
      relationshipsEnd: relationshipsEnd,
      synthesized: synthesized
    };
  }

  function analyzeLocal(safeSteps, result) {
    var walk = describeLocalWalk(safeSteps, result, 0, safeSteps.length, {});
    var end = safeSteps.length;
    result.intro = 'Local Search starts from the entities closest to the question and walks their'
        + ' neighbourhood in the graph. Every entity, relationship and passage it touched is listed'
        + ' below, in order, with the reason it was picked.';
    addPhase(result, 'Seed entities',
        'The question is embedded and compared with every entity; the closest ones (up to 3) become'
        + ' starting points. Without embeddings, the entity sharing the most words with the question is used.',
        0, walk.seedsEnd);
    if (walk.synthesized) {
      addPhase(result, 'Graph facts',
          'Relationships touching a seed, heaviest first (up to 10). They are the graph facts the'
          + ' answer is written from.',
          walk.seedsEnd, walk.relationshipsEnd);
      addPhase(result, 'Source passages',
          'The passages those seeds and relationships were extracted from, ranked by how many of them'
          + ' cite each (up to 5). The answer is then written from seeds, facts and passages, citing'
          + ' passages as [n].',
          walk.relationshipsEnd, end);
    } else if (end > walk.seedsEnd) {
      addPhase(result, 'Relationship hop',
          'From the seed, the relationship whose wording best matches the question is followed to the'
          + ' entity at its other end. That single hop is the answer.',
          walk.seedsEnd, end);
    } else {
      result.intro += ' No relationship around the seed matched the question, so the closest entity'
          + ' alone is the answer.';
    }
  }

  function analyzeGlobal(safeSteps, result) {
    var synthesized = hasKind(safeSteps, 'TEXT_UNIT', 0, safeSteps.length);
    var rank = 0;
    for (var i = 0; i < safeSteps.length; i += 1) {
      var step = safeSteps[i];
      if (step.kind === 'COMMUNITY') {
        rank += 1;
        result.reasons[i] = rank === 1
            ? 'Community ranked 1: of all community summaries, this one scored highest against the question.'
            : 'Community ranked ' + rank + ': its summary is among the best-scoring against the question,'
                + ' so it is read as well.';
      } else if (step.kind === 'TEXT_UNIT') {
        result.reasons[i] = 'A passage cited by members of the community just read (up to 2 per community),'
            + ' ranked by how strongly the relationships inside that community cite it. The answer can'
            + ' cite it as [n].';
      } else {
        result.reasons[i] = 'Touched while answering.';
      }
    }
    result.intro = 'Global Search does not start from a single entity: it reads the community summaries,'
        + ' the graph’s bird’s-eye view, and picks the ones that best fit the question.';
    addPhase(result, 'Community pass',
        'Every community summary is scored against the question, by meaning (or by shared words when'
        + ' the corpus has no embeddings). The best-scoring communities are read in rank order.'
        + (synthesized
            ? ' After each community, the passages its members cite are read too; the answer is written'
                + ' from all of it and cites passages as [n].'
            : ' The best summary is quoted as the answer.'),
        0, safeSteps.length);
  }

  function analyzeDrift(safeSteps, result) {
    var spawns = [];
    var synthesisIndex = -1;
    safeSteps.forEach(function (step, i) {
      if (step.kind === 'SUB_QUESTION_SPAWNED') {
        spawns.push(i);
      } else if (step.kind === 'SYNTHESIS' && synthesisIndex === -1) {
        synthesisIndex = i;
      }
    });
    var synthesized = hasKind(safeSteps, 'TEXT_UNIT', 0, safeSteps.length);
    var firstSpawn = spawns.length > 0 ? spawns[0] : (synthesisIndex === -1 ? safeSteps.length : synthesisIndex);
    var branchByCommunity = {};
    spawns.forEach(function (spawnIndex, n) {
      var id = safeSteps[spawnIndex].identifier;
      if (id && branchByCommunity[id] === undefined) {
        branchByCommunity[id] = n + 1;
      }
    });

    result.intro = 'DRIFT Search first looks across the whole graph, then drills down: each promising'
        + ' community becomes a focused sub-question that is answered like a Local Search, and the'
        + ' branches are merged into one answer.';

    // Phase 1: community pass.
    for (var i = 0; i < firstSpawn; i += 1) {
      var step = safeSteps[i];
      if (step.kind === 'COMMUNITY') {
        var branch = branchByCommunity[step.identifier];
        result.reasons[i] = branch
            ? 'Its summary scored well enough against the question to spawn branch ' + branch + '.'
            : 'Its summary was scored against the question but did not score well enough to spawn a'
                + ' branch of its own.';
      } else {
        result.reasons[i] = 'Touched while answering.';
      }
    }
    addPhase(result, 'Community pass',
        'Every community summary is scored against the question. The best-scoring ones (highlighted'
        + ' on the graph) each get their own branch: a focused sub-question answered by a local walk.',
        0, firstSpawn);

    // Phase 2: one phase per branch.
    spawns.forEach(function (spawnIndex, n) {
      var branchEnd = n + 1 < spawns.length
          ? spawns[n + 1]
          : (synthesisIndex === -1 ? safeSteps.length : synthesisIndex);
      branchEnd = Math.max(branchEnd, spawnIndex + 1);
      var spawn = safeSteps[spawnIndex];
      result.reasons[spawnIndex] = 'The community' + (spawn.identifier ? ' ' + spawn.identifier : '')
          + ' (highlighted) is turned into a focused sub-question for this branch, so the local walk'
          + ' searches for exactly the part of the question it can answer.';
      describeLocalWalk(safeSteps, result, spawnIndex + 1, branchEnd, {
        owner: 'this branch’s sub-question',
        passageLead: 'This branch reads a source passage that its entities and relationships cite.'
      });
      addPhase(result, 'Branch ' + (n + 1) + ' of ' + spawns.length,
          'Local search for this branch’s sub-question: it matches entities and follows their'
          + ' relationships on the graph' + (synthesized ? ', then reads the passages they cite.' : '.'),
          spawnIndex, branchEnd);
    });

    // Phase 3: synthesis.
    if (synthesisIndex !== -1) {
      var synthesisWhy;
      if (spawns.length === 0) {
        synthesisWhy = 'No community scored well enough to spawn a sub-question, so there was nothing to'
            + ' drill into and no answer could be written.';
      } else if (synthesized) {
        synthesisWhy = 'One answer is written from every branch’s passages and graph facts together;'
            + ' its [n] markers cite the passages read.';
      } else {
        synthesisWhy = 'The first branch whose local search followed a relationship becomes the answer.';
      }
      for (var k = synthesisIndex; k < safeSteps.length; k += 1) {
        result.reasons[k] = synthesisWhy;
      }
      addPhase(result, 'Synthesis', synthesisWhy, synthesisIndex, safeSteps.length);
    }
  }

  function analyzeVector(safeSteps, result) {
    var synthesisIndex = -1;
    var chunkCount = 0;
    safeSteps.forEach(function (step, i) {
      if (step.kind === 'SYNTHESIS' && synthesisIndex === -1) {
        synthesisIndex = i;
      }
      if (step.kind === 'VECTOR_CHUNK') {
        chunkCount += 1;
      }
    });
    var chunksEnd = synthesisIndex === -1 ? safeSteps.length : synthesisIndex;
    var rank = 0;
    safeSteps.forEach(function (step, i) {
      if (step.kind === 'VECTOR_QUERY_EMBEDDED') {
        result.reasons[i] = 'The question itself, turned into a vector. Every chunk is compared against'
            + ' this vector; nothing in the graph is consulted.';
      } else if (step.kind === 'VECTOR_CHUNK') {
        rank += 1;
        var score = chunkScore(step);
        result.reasons[i] = 'Rank ' + rank + ' of ' + chunkCount + ' by cosine similarity to the question'
            + (score ? ' (' + score + ')' : '') + '. Only the top ' + chunkCount
            + ' chunks are kept; a chunk is plain text, not an entity or relationship.';
      } else if (step.kind === 'SYNTHESIS') {
        result.reasons[i] = 'The answer is written from those retrieved chunks alone and cites them as [n]'
            + ' where it uses them.';
      } else {
        result.reasons[i] = 'Touched while answering.';
      }
    });
    result.intro = 'Vector Search is the baseline without a graph: it embeds the question and retrieves'
        + ' the text chunks most similar to it. Compare it with a GraphRAG trace of the same question.';
    addPhase(result, 'Embed the question',
        'The question is turned into a vector with the same embedding model as the corpus chunks, so'
        + ' it can be compared with them.',
        0, Math.min(1, chunksEnd));
    addPhase(result, 'Nearest chunks',
        'Every chunk is compared with the question by cosine similarity and the top ' + chunkCount
        + ' are kept. No entities, relationships or communities are involved.',
        Math.min(1, chunksEnd), chunksEnd);
    if (synthesisIndex !== -1) {
      addPhase(result, 'Synthesis', 'The answer is written from those chunks alone.',
          synthesisIndex, safeSteps.length);
    }
  }

  // ---- helpers ----

  function clip(text, max) {
    var s = String(text == null ? '' : text);
    return s.length > max ? s.slice(0, max - 1) + '…' : s;
  }

  function pad(value) {
    var text = String(value);
    return text.length < 2 ? '0' + text : text;
  }
})();
