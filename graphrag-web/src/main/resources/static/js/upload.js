(function () {
  'use strict';

  var fileInput = document.getElementById('corpus-file-input');
  var corpusChip = document.getElementById('corpus-chip');
  var errorBanner = document.getElementById('error-banner');
  var demoButton = document.getElementById('demo-dataset-button');
  var eventSource = null;

  if (!fileInput || !corpusChip || !errorBanner || !demoButton) {
    return;
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
    setControlsDisabled(true);

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
          handleSuccess(result.body);
        } else {
          showErrorBanner(errorMessage(result.body));
        }
      })
      .catch(function () {
        showErrorBanner('Upload failed. Please check your connection and try again.');
      })
      .finally(function () {
        fileInput.value = '';
        setControlsDisabled(false);
      });
  });

  demoButton.addEventListener('click', function () {
    hideErrorBanner();
    setControlsDisabled(true);

    fetch('/api/corpora/demo', { method: 'POST' })
      .then(function (response) {
        return response.json().then(function (body) {
          return { ok: response.ok, body: body };
        });
      })
      .then(function (result) {
        if (result.ok) {
          handleSuccess(result.body);
        } else {
          showErrorBanner(errorMessage(result.body));
        }
      })
      .catch(function () {
        showErrorBanner('Demo dataset load failed. Please try again.');
      })
      .finally(function () {
        setControlsDisabled(false);
      });
  });

  function errorMessage(body) {
    return body && body.error ? body.error : 'Upload failed.';
  }

  function handleSuccess(body) {
    showCorpusChip(body);
    startProgressStream(body.corpusId);
  }

  function showCorpusChip(body) {
    var names = body.displayName || (body.documentNames || []).join(', ');
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
  }

  function showErrorBanner(message) {
    errorBanner.textContent = message;
    errorBanner.hidden = false;
  }

  function hideErrorBanner() {
    errorBanner.hidden = true;
    errorBanner.textContent = '';
  }

  function setControlsDisabled(disabled) {
    fileInput.disabled = disabled;
    demoButton.disabled = disabled;
  }

  function startProgressStream(corpusId) {
    if (!corpusId) {
      return;
    }
    if (eventSource) {
      eventSource.close();
    }
    eventSource = new EventSource('/api/corpora/' + encodeURIComponent(corpusId) + '/progress');

    eventSource.addEventListener('error', function () {
      showErrorBanner('Progress stream disconnected.');
      eventSource.close();
    });

    eventSource.addEventListener('entity-extracted', function () {
      hideErrorBanner();
    });

    eventSource.addEventListener('ingestion-complete', function () {
      hideErrorBanner();
      eventSource.close();
    });

    eventSource.addEventListener('ingestion-error', function (event) {
      try {
        var payload = JSON.parse(event.data);
        if (payload && payload.data && payload.data.message) {
          showErrorBanner(payload.data.message);
        }
      } catch (e) {
        showErrorBanner('Ingestion failed.');
      }
    });
  }
})();
