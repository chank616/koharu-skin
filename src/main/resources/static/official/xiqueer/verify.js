(function () {
  'use strict';

  const root = document.querySelector('#xiqueer-verification');
  if (!root || root.dataset.active !== '1') return;

  const status = document.querySelector('#xiqueer-status');
  const reload = document.querySelector('#xiqueer-reload');
  let stopped = false;

  new QRCode(document.querySelector('#xiqueer-qrcode'), {
    text: root.dataset.challenge,
    width: 256,
    height: 256,
    correctLevel: QRCode.CorrectLevel.H,
  });

  const renderStatus = (message, type, spinning) => {
    status.className = `mt-3 mb-2 text-${type}`;
    status.textContent = '';
    if (spinning) {
      const icon = document.createElement('i');
      icon.className = 'fas fa-spinner fa-spin mr-1';
      status.appendChild(icon);
    }
    status.appendChild(document.createTextNode(message));
  };

  const schedule = (delay) => {
    if (!stopped) window.setTimeout(poll, delay);
  };

  const poll = async () => {
    const result = await blessing.fetch.post(root.dataset.statusUrl, {});

    if (result.code === 0) {
      stopped = true;
      renderStatus(result.message || root.dataset.success, 'success', false);
      return;
    }

    if (result.code === 1) {
      renderStatus(result.message || root.dataset.waiting, 'info', true);
      schedule(2000);
      return;
    }

    renderStatus(result.message, 'danger', false);
    if (result.data && result.data.reload) {
      stopped = true;
      reload.classList.remove('d-none');
    } else {
      schedule(4000);
    }
  };

  schedule(500);
})();
