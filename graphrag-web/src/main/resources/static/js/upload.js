(function () {
  'use strict';

  var fileInput = document.getElementById('corpus-file-input');
  var demoButton = document.getElementById('demo-dataset-button');
  var corpusChip = document.getElementById('corpus-chip');
  var errorBanner = document.getElementById('error-banner');
  var canvasIdle = document.getElementById('canvas-idle');
  var graphCanvasEl = document.getElementById('graph-canvas');
  var chatPanel = document.getElementById('chat-panel');
  var chatThread = document.getElementById('chat-thread');
  var chatForm = document.getElementById('chat-form');
  var chatInput = document.getElementById('chat-input');
  var modeButtons = document.querySelectorAll('.mode-button');
  var modeHint = document.getElementById('mode-hint');
  var communityToggleWrap = document.getElementById('community-toggle-wrap');
  var communityVisualizationToggle = document.getElementById('community-visualization-toggle');
  var activeProgressSource = null;
  var activeCorpusId = null;
  var currentSearchMode = 'LOCAL';

  if (!fileInput || !corpusChip || !errorBanner) {
    return;
  }

  function setModeHint(mode) {
    currentSearchMode = mode;
    if (mode === 'GLOBAL') {
      modeHint.textContent = 'Global Search aggregates information across Communities to answer broader, corpus-level questions.';
    } else {
      modeHint.textContent = 'Local Search traverses specific Entities and Relationships around your question.';
    }

    modeButtons.forEach(function (button) {
      var isActive = button.dataset.mode === mode;
      button.classList.toggle('active', isActive);
      button.setAttribute('aria-pressed', isActive ? 'true' : 'false');
    });
  }

  modeButtons.forEach(function (button) {
    button.addEventListener('click', function () {
      setModeHint(button.dataset.mode || 'LOCAL');
    });
  });

  if (communityVisualizationToggle) {
    communityVisualizationToggle.addEventListener('change', function () {
      if (window.GraphCanvas) {
        window.GraphCanvas.setHullsVisible(communityVisualizationToggle.checked);
      }
    });
  }

  if (chatForm) {
    chatForm.addEventListener('submit', function (event) {
      event.preventDefault();
      if (!activeCorpusId) {
        showErrorBanner('Choose a corpus before asking a question.');
        return;
      }

      var question = (chatInput ? chatInput.value : '').trim();
      if (!question) {
        showErrorBanner('Please enter a question first.');
        return;
      }

      appendMessage('question', question);
      if (chatInput) {
        chatInput.value = '';
      }
      hideErrorBanner();

      fetch('/api/corpora/' + activeCorpusId + '/query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: question, mode: currentSearchMode })
      })
        .then(function (response) {
          return response.json().then(function (body) {
            return { ok: response.ok, body: body };
          });
        })
        .then(function (result) {
          if (result.ok) {
            appendAnswer(result.body.answer, result.body.mode || currentSearchMode);
          } else {
            showErrorBanner(errorMessage(result.body));
          }
        })
        .catch(function () {
          showErrorBanner('The question could not be answered. Please try again.');
        });
    });
  }

  if (demoButton) {
    var demoButtonDefaultLabel = demoButton.textContent;
    demoButton.addEventListener('click', function () {
      hideErrorBanner();
      fileInput.disabled = true;
      demoButton.disabled = true;
      demoButton.setAttribute('aria-busy', 'true');
      demoButton.textContent = 'Loading demo dataset…';

      fetch('/api/corpora/demo', {
        method: 'POST'
      })
        .then(function (response) {
          return response.json().then(function (body) {
            return { ok: response.ok, body: body };
          });
        })
        .then(function (result) {
          if (result.ok) {
            showCorpusChip(result.body);
          } else {
            showErrorBanner(errorMessage(result.body));
          }
        })
        .catch(function () {
          showErrorBanner('The demo dataset could not be loaded. Please try again.');
        })
        .finally(function () {
          fileInput.disabled = false;
          demoButton.disabled = false;
          demoButton.removeAttribute('aria-busy');
          demoButton.textContent = demoButtonDefaultLabel;
        });
    });
  }

  fileInput.addEventListener('change', function () {
    var files = fileInput.files;
    if (!files || files.length === 0) {
      return;
    }

    var formData = new FormData();
    for (var i = 0; i < files.length; i += 1) {
      formData.append('files', files[i]);
    }

    hideErrorBanner();
    fileInput.disabled = true;
    if (demoButton) {
      demoButton.disabled = true;
    }

    fetch('/api/corpora', {
      method: 'POST',
      body: formData
    })
      .then(function (response) {
        return response.json().then(function (body) {
          return { ok: response.ok, body: body };
        });
      })
      .then(function (result) {
        if (result.ok) {
          showCorpusChip(result.body);
        } else {
          showErrorBanner(errorMessage(result.body));
        }
      })
      .catch(function () {
        showErrorBanner('Upload failed. Please check your connection and try again.');
      })
      .finally(function () {
        fileInput.value = '';
        fileInput.disabled = false;
        if (demoButton) {
          demoButton.disabled = false;
        }
      });
  });

  function errorMessage(body) {
    return body && body.error ? body.error : 'Upload failed.';
  }

  function showCorpusChip(body) {
    var names = body && body.name ? body.name : (body.documentNames || []).join(', ');
    var count = body.documentCount || 0;
    var unit = count === 1 ? 'document' : 'documents';

    corpusChip.textContent = '';

    var dot = document.createElement('span');
    dot.className = 'status-dot';
    dot.setAttribute('aria-hidden', 'true');
    corpusChip.appendChild(dot);

    var label = document.createElement('span');
    label.textContent = names + ' · ' + count + ' ' + unit;
    corpusChip.appendChild(label);

    corpusChip.hidden = false;
    activeCorpusId = body && body.corpusId ? body.corpusId : activeCorpusId;
    if (canvasIdle) {
      canvasIdle.hidden = true;
    }
    if (chatPanel) {
      chatPanel.hidden = false;
    }
    if (communityToggleWrap) {
      communityToggleWrap.hidden = false;
    }
    if (communityVisualizationToggle) {
      communityVisualizationToggle.checked = true;
    }
    if (graphCanvasEl) {
      graphCanvasEl.hidden = false;
    }
    if (window.GraphCanvas) {
      window.GraphCanvas.init(graphCanvasEl);
      window.GraphCanvas.setHullsVisible(true);
    }
    connectProgressStream(body && body.corpusId);
  }

  function appendMessage(kind, text) {
    if (!chatThread) {
      return;
    }
    var message = document.createElement('div');
    message.className = 'message ' + kind;
    if (kind === 'answer') {
      var tag = document.createElement('div');
      tag.className = 'answer-tag';
      tag.textContent = (currentSearchMode === 'GLOBAL' ? 'Global Search' : 'Local Search') + ' · Answer';
      message.appendChild(tag);
      message.dataset.mode = currentSearchMode;
      var content = document.createElement('span');
      content.textContent = text;
      message.appendChild(content);
    } else {
      message.textContent = text;
    }
    chatThread.appendChild(message);
    chatThread.scrollTop = chatThread.scrollHeight;
  }

  function appendAnswer(text, mode) {
    var activeMode = mode || currentSearchMode;
    var answerText = text || 'No answer was returned.';
    if (!chatThread) {
      return;
    }
    var message = document.createElement('div');
    message.className = 'message answer';
    message.dataset.mode = activeMode;

    var tag = document.createElement('div');
    tag.className = 'answer-tag';
    tag.textContent = (activeMode === 'GLOBAL' ? 'Global Search' : 'Local Search') + ' · Answer';
    message.appendChild(tag);

    var content = document.createElement('span');
    content.textContent = answerText;
    message.appendChild(content);

    chatThread.appendChild(message);
    chatThread.scrollTop = chatThread.scrollHeight;
  }

  function connectProgressStream(corpusId) {
    if (!corpusId || typeof EventSource === 'undefined') {
      return;
    }

    if (activeProgressSource) {
      activeProgressSource.close();
    }

    activeProgressSource = new EventSource('/api/corpora/' + corpusId + '/progress');
    activeProgressSource.addEventListener('heartbeat', function (event) {
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.message) {
          console.log('Progress heartbeat:', payload.data.message);
        }
      } catch (e) {
        console.warn('Invalid SSE heartbeat payload', e);
      }
    });

    activeProgressSource.addEventListener('ingestion-started', function (event) {
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.message) {
          console.log('Ingestion started:', payload.data.message);
        }
      } catch (e) {
        console.warn('Invalid SSE ingestion payload', e);
      }
    });

    activeProgressSource.addEventListener('entity-extracted', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        console.log('Entity extracted:', data);
        if (data && window.GraphCanvas) {
          window.GraphCanvas.addEntity(data.identity, data.name, data.type);
        }
      } catch (e) {
        console.warn('Invalid SSE entity-extracted payload', e);
      }
    });

    activeProgressSource.addEventListener('relationship-extracted', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        console.log('Relationship extracted:', data);
        if (data && window.GraphCanvas) {
          window.GraphCanvas.addRelationship(
            data.sourceIdentity, data.targetIdentity, data.type, data.source, data.target);
        }
      } catch (e) {
        console.warn('Invalid SSE relationship-extracted payload', e);
      }
    });

    activeProgressSource.addEventListener('community-detected', function (event) {
      try {
        var payload = JSON.parse(event.data);
        var data = payload && payload.data;
        console.log('Community detected:', data);
        if (data && window.GraphCanvas) {
          window.GraphCanvas.addCommunity(data.communityId, data.memberEntityIdentities, data.summary);
        }
      } catch (e) {
        console.warn('Invalid SSE community-detected payload', e);
      }
    });

    activeProgressSource.addEventListener('error', function (event) {
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.error) {
          showErrorBanner(payload.data.error);
        }
      } catch (e) {
        console.warn('Invalid SSE error payload', e);
      }
    });

    activeProgressSource.onerror = function () {
      activeProgressSource.close();
      activeProgressSource = null;
    };
  }

  function showErrorBanner(message) {
    errorBanner.textContent = message;
    errorBanner.hidden = false;
  }

  function hideErrorBanner() {
    errorBanner.hidden = true;
    errorBanner.textContent = '';
  }
})();
