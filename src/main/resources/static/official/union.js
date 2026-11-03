(() => {
  const base = window.blessing.base_url
  const message = document.getElementById('union-message')
  async function request(path, options = {}) {
    const response = await fetch(path, { ...options, headers: { Accept: 'application/json', 'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]').content }, signal: AbortSignal.timeout(15000) })
    const text = await response.text()
    const data = text ? JSON.parse(text) : {}
    if (!response.ok || data.code) throw new Error(data.message || '联盟操作失败，请稍后重试。')
    return data
  }
  document.querySelectorAll('[data-union-form]').forEach(form => {
    form.addEventListener('submit', async event => {
      event.preventDefault()
      if (form.dataset.confirm && !window.confirm(form.dataset.confirm)) return
      const button = form.querySelector('button')
      button.disabled = true
      message.classList.add('d-none')
      try {
        const result = await request(form.action, { method: 'POST', body: new URLSearchParams(new FormData(form)) })
        if (form.hasAttribute('data-token-result')) form.querySelector('output').textContent = result.token
        else window.location.reload()
      } catch (error) {
        message.textContent = error.message
        message.classList.remove('d-none')
      } finally { button.disabled = false }
    })
  })
  request(`${base}/union/security/level`).then(level => {
    const label = document.getElementById('union-security-level')
    label.textContent = `SL${level}`
    label.className = level === 3 ? 'badge badge-success' : 'badge badge-warning'
  }).catch(() => { document.getElementById('union-security-level').textContent = '暂时无法读取' })
  request(`${base}/union/description`).then(result => {
    document.getElementById('union-description').innerHTML = result.html
  }).catch(() => { document.getElementById('union-description').textContent = '联盟说明暂时不可用。' })
  const backends = new Map()
  document.querySelectorAll('[data-union-backend]').forEach(element => {
    const root = element.dataset.unionBackend
    if (!root) return
    if (!backends.has(root)) backends.set(root, fetch(`${root.replace(/\/+$/, '')}/api/yggdrasil`, { credentials: 'omit', signal: AbortSignal.timeout(5000) })
      .then(response => { if (!response.ok) throw new Error(); return response.json() }).then(data => data.meta.serverName))
    backends.get(root).then(name => { element.textContent = name }).catch(() => {})
  })
})()
