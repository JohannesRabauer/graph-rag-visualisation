(function () {
  'use strict';

  var container = document.getElementById('drift-tree');
  var model = emptyModel();

  if (!container) {
    return;
  }

  function emptyModel() {
    return {
      branches: [],
      firstBranchIndex: -1,
      synthesisIndex: -1,
      signature: ''
    };
  }

  function analyzeSteps(steps) {
    var safeSteps = Array.isArray(steps) ? steps : [];
    var branches = [];
    var synthesisIndex = -1;

    for (var i = 0; i < safeSteps.length; i += 1) {
      var step = safeSteps[i];
      if (!step) {
        continue;
      }
      if (step.kind === 'SUB_QUESTION_SPAWNED') {
        branches.push({
          startIndex: i,
          endIndex: safeSteps.length,
          label: step.label || ('Sub-question ' + (branches.length + 1))
        });
      } else if (step.kind === 'SYNTHESIS' && synthesisIndex === -1) {
        synthesisIndex = i;
      }
    }

    for (var j = 0; j < branches.length; j += 1) {
      var nextSpawnIndex = j < branches.length - 1 ? branches[j + 1].startIndex : safeSteps.length;
      branches[j].endIndex = synthesisIndex === -1 ? nextSpawnIndex : Math.min(nextSpawnIndex, synthesisIndex);
    }

    var firstBranchIndex = branches.length > 0 ? branches[0].startIndex : -1;
    return {
      branches: branches,
      firstBranchIndex: firstBranchIndex,
      synthesisIndex: synthesisIndex,
      signature: branches.map(function (branch) {
        return branch.startIndex + ':' + branch.endIndex + ':' + branch.label;
      }).join('|') + '::' + synthesisIndex
    };
  }

  function showContainer() {
    container.hidden = false;
    container.setAttribute('aria-hidden', 'false');
  }

  function hideContainer() {
    container.hidden = true;
    container.setAttribute('aria-hidden', 'true');
  }

  function ensureBuilt(steps) {
    var nextModel = analyzeSteps(steps);
    if (nextModel.branches.length === 0) {
      clear();
      return null;
    }
    if (model.signature !== nextModel.signature || container.childElementCount === 0) {
      build(steps);
    }
    return model;
  }

  function createNode(text, className) {
    var node = document.createElement('div');
    node.className = 'drift-tree-node ' + className;
    node.textContent = text;
    return node;
  }

  function createLine(className) {
    var line = document.createElement('div');
    line.className = 'drift-tree-line ' + className;
    return line;
  }

  function build(steps) {
    model = analyzeSteps(steps);
    container.textContent = '';

    if (model.branches.length === 0) {
      hideContainer();
      return;
    }

    var root = createNode('Community pass', 'drift-tree-root');
    var topStem = document.createElement('div');
    topStem.className = 'drift-tree-stem';
    var branches = document.createElement('div');
    branches.className = 'drift-tree-branches';
    branches.style.setProperty('--branch-count', String(model.branches.length));

    var topRail = document.createElement('div');
    topRail.className = 'drift-tree-branch-rail';
    branches.appendChild(topRail);

    var bottomRail = document.createElement('div');
    bottomRail.className = 'drift-tree-converge-rail';
    branches.appendChild(bottomRail);

    model.branches.forEach(function (branch, index) {
      var branchEl = document.createElement('div');
      branchEl.className = 'drift-tree-branch';
      branchEl.dataset.branchIndex = String(index);

      var topLine = createLine('drift-tree-line-top');
      var node = createNode(branch.label, 'drift-tree-branch-node');
      var resolved = document.createElement('span');
      resolved.className = 'drift-tree-resolved';
      resolved.textContent = '\u2713 resolved';
      var bottomLine = createLine('drift-tree-line-bottom');

      branchEl.appendChild(topLine);
      branchEl.appendChild(node);
      branchEl.appendChild(resolved);
      branchEl.appendChild(bottomLine);
      branches.appendChild(branchEl);
    });

    var bottomStem = document.createElement('div');
    bottomStem.className = 'drift-tree-stem';
    var finalNode = createNode('Re-rank + synthesize', 'drift-tree-final');

    container.appendChild(root);
    container.appendChild(topStem);
    container.appendChild(branches);
    container.appendChild(bottomStem);
    container.appendChild(finalNode);

    showContainer();
  }

  function highlightStep(steps, currentIndex) {
    var activeModel = ensureBuilt(steps);
    if (!activeModel || !steps || steps.length === 0) {
      return;
    }

    var clampedIndex = Math.max(0, Math.min(steps.length - 1, currentIndex));
    var root = container.querySelector('.drift-tree-root');
    var finalNode = container.querySelector('.drift-tree-final');
    var rootIsCurrent = clampedIndex < activeModel.firstBranchIndex;
    var finalIsCurrent = activeModel.synthesisIndex !== -1 && clampedIndex >= activeModel.synthesisIndex;

    if (root) {
      root.classList.toggle('is-current', rootIsCurrent);
      root.setAttribute('aria-current', rootIsCurrent ? 'step' : 'false');
    }
    if (finalNode) {
      finalNode.classList.toggle('is-current', finalIsCurrent);
      finalNode.setAttribute('aria-current', finalIsCurrent ? 'step' : 'false');
    }

    var branchEls = container.querySelectorAll('.drift-tree-branch');
    for (var i = 0; i < branchEls.length; i += 1) {
      var branchModel = activeModel.branches[i];
      var branchEl = branchEls[i];
      var branchNode = branchEl.querySelector('.drift-tree-branch-node');
      var isCurrent = clampedIndex >= branchModel.startIndex && clampedIndex < branchModel.endIndex;
      var isResolved = clampedIndex >= branchModel.endIndex;
      var isUpcoming = clampedIndex < branchModel.startIndex;

      branchEl.classList.toggle('is-current', isCurrent);
      branchEl.classList.toggle('is-resolved', isResolved);
      branchEl.classList.toggle('is-upcoming', isUpcoming);

      if (branchNode) {
        branchNode.classList.toggle('is-current', isCurrent);
        branchNode.setAttribute('aria-current', isCurrent ? 'step' : 'false');
      }

      var lines = branchEl.querySelectorAll('.drift-tree-line');
      for (var j = 0; j < lines.length; j += 1) {
        lines[j].classList.toggle('is-upcoming', isUpcoming);
      }
    }
  }

  function clear() {
    container.textContent = '';
    hideContainer();
    model = emptyModel();
  }

  window.DriftTree = {
    build: build,
    highlightStep: highlightStep,
    clear: clear
  };
})();
