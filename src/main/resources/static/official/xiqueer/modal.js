(function () {
  'use strict';

  if (document.querySelector('#xiqueer-verification')) return;

  const challengeUrl = '/user/xiqueer-verification/challenge';
  const statusUrl = '/user/xiqueer-verification/status';
  const routeSuffix = '/user/xiqueer-verification';
  const fallback = {
    title: '喜鹊儿认证',
    'support-notice': '目前只支持华水，其他学校尚未对接。',
    open: '使用喜鹊儿 APP 扫码认证',
    instructions: '请使用喜鹊儿 APP 扫描二维码，认证成功后当前页面会自动更新。',
    'current-email': '当前待验证邮箱：:email',
    loading: '正在生成二维码……',
    waiting: '等待喜鹊儿 APP 扫码……',
    success: '认证成功。',
    refresh: '生成新二维码',
    close: '关闭',
    error: '无法加载认证信息，请稍后重试。',
  };

  let modal;
  let pollTimer;
  let generation = 0;

  const translate = (key, replacements) => {
    const path = `xiqueer-verification.modal.${key}`;
    const translated = blessing.t(path, replacements || {});
    if (translated !== path) return translated;

    let value = fallback[key] || path;
    Object.keys(replacements || {}).forEach((name) => {
      value = value.replace(`:${name}`, replacements[name]);
    });
    return value;
  };

  const stopPolling = () => {
    generation += 1;
    window.clearTimeout(pollTimer);
  };

  const ensureModal = () => {
    if (modal) return modal;

    const wrapper = document.createElement('div');
    wrapper.innerHTML = `
      <div id="xiqueer-verification-modal" class="modal fade" tabindex="-1" role="dialog" aria-hidden="true">
        <div class="modal-dialog modal-dialog-centered" role="document">
          <div class="modal-content">
            <div class="modal-header">
              <h5 class="modal-title"><i class="fas fa-qrcode mr-1"></i>${translate('title')}</h5>
              <button type="button" class="close" data-dismiss="modal" aria-label="${translate('close')}">
                <span aria-hidden="true">&times;</span>
              </button>
            </div>
            <div class="modal-body text-center">
              <div class="alert alert-info text-left">
                <i class="fas fa-info-circle mr-1"></i>${translate('support-notice')}
              </div>
              <p data-xiqueer-instructions>${translate('instructions')}</p>
              <p class="text-muted small d-none" data-xiqueer-email></p>
              <div class="d-inline-block bg-white p-3 border rounded d-none" data-xiqueer-qrcode></div>
              <p class="mt-3 mb-2 text-info" data-xiqueer-status aria-live="polite"></p>
              <button type="button" class="btn btn-outline-primary d-none" data-xiqueer-refresh>
                <i class="fas fa-redo mr-1"></i>${translate('refresh')}
              </button>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-dismiss="modal">${translate('close')}</button>
            </div>
          </div>
        </div>
      </div>`;

    modal = wrapper.firstElementChild;
    document.body.appendChild(modal);
    window.$(modal).on('hidden.bs.modal', stopPolling);
    modal.querySelector('[data-xiqueer-refresh]').addEventListener('click', loadChallenge);

    return modal;
  };

  const renderStatus = (message, type, spinning) => {
    const status = ensureModal().querySelector('[data-xiqueer-status]');
    status.className = `mt-3 mb-2 text-${type}`;
    status.textContent = '';

    if (spinning) {
      const icon = document.createElement('i');
      icon.className = 'fas fa-spinner fa-spin mr-1';
      status.appendChild(icon);
    }

    status.appendChild(document.createTextNode(message));
  };

  const showRefresh = (visible) => {
    ensureModal()
      .querySelector('[data-xiqueer-refresh]')
      .classList.toggle('d-none', !visible);
  };

  const finishOnCurrentPage = () => {
    const verificationNotice = document.querySelector('#email-verification');
    if (verificationNotice) verificationNotice.classList.add('d-none');
  };

  const schedulePoll = (token, delay) => {
    if (token !== generation) return;
    pollTimer = window.setTimeout(() => poll(token), delay);
  };

  const poll = async (token) => {
    if (token !== generation) return;

    const result = await blessing.fetch.post(statusUrl, {});
    if (token !== generation) return;

    if (result.code === 0) {
      stopPolling();
      renderStatus(result.message || translate('success'), 'success', false);
      showRefresh(false);
      finishOnCurrentPage();
      return;
    }

    if (result.code === 1) {
      renderStatus(result.message || translate('waiting'), 'info', true);
      schedulePoll(token, 2000);
      return;
    }

    renderStatus(result.message || translate('error'), 'danger', false);
    if (result.data && result.data.reload) {
      stopPolling();
      showRefresh(true);
    } else {
      schedulePoll(token, 4000);
    }
  };

  async function loadChallenge() {
    stopPolling();
    const token = generation;
    const currentModal = ensureModal();
    const qr = currentModal.querySelector('[data-xiqueer-qrcode]');
    const email = currentModal.querySelector('[data-xiqueer-email]');

    qr.textContent = '';
    qr.classList.add('d-none');
    email.textContent = '';
    email.classList.add('d-none');
    showRefresh(false);
    renderStatus(translate('loading'), 'info', true);

    const result = await blessing.fetch.get(challengeUrl);
    if (token !== generation) return;

    if (result.code !== 0) {
      renderStatus(result.message || translate('error'), 'danger', false);
      showRefresh(true);
      return;
    }

    if (result.data && result.data.verified) {
      stopPolling();
      renderStatus(result.message || translate('success'), 'success', false);
      finishOnCurrentPage();
      return;
    }

    if (!result.data || !result.data.challenge) {
      renderStatus(translate('error'), 'danger', false);
      showRefresh(true);
      return;
    }

    email.textContent = translate('current-email', { email: result.data.email });
    email.classList.remove('d-none');
    qr.classList.remove('d-none');
    new QRCode(qr, {
      text: result.data.challenge,
      width: 256,
      height: 256,
      correctLevel: QRCode.CorrectLevel.H,
    });
    renderStatus(translate('waiting'), 'info', true);
    schedulePoll(token, 500);
  }

  const openModal = () => {
    const currentModal = ensureModal();
    window.$(currentModal).modal('show');
    loadChallenge();
  };

  const isVerificationLink = (anchor) => {
    try {
      const url = new URL(anchor.href, window.location.href);
      return url.origin === window.location.origin
        && url.pathname.replace(/\/$/, '').endsWith(routeSuffix);
    } catch (error) {
      return false;
    }
  };

  document.addEventListener('click', (event) => {
    if (!(event.target instanceof Element)) return;
    const anchor = event.target.closest('a');
    if (!anchor || !isVerificationLink(anchor)) return;

    event.preventDefault();
    openModal();
  });

  const addDashboardEntry = () => {
    const callout = document.querySelector('#email-verification .callout-info');
    if (!callout || callout.querySelector('[data-xiqueer-open]')) return;

    const paragraph = document.createElement('p');
    paragraph.className = 'mb-0 mt-2';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-sm btn-outline-primary';
    button.dataset.xiqueerOpen = '1';
    const icon = document.createElement('i');
    icon.className = 'fas fa-qrcode mr-1';
    button.appendChild(icon);
    button.appendChild(document.createTextNode(translate('open')));
    button.addEventListener('click', openModal);
    paragraph.appendChild(button);
    callout.appendChild(paragraph);
  };

  addDashboardEntry();
  const verificationRoot = document.querySelector('#email-verification');
  if (verificationRoot) {
    new MutationObserver(addDashboardEntry).observe(verificationRoot, {
      childList: true,
      subtree: true,
    });
  }
})();
