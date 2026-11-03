// 迁移自 Blessing Skin 的一言扩展，保留原服务地址和纯文本显示方式。
;(async () => {
  const container = document.querySelector('.content-header') || document.querySelector('.content > .container, .content > .container-fluid')
  if (!container) return
  try {
    const response = await fetch('https://v1.hitokoto.cn?encode=text')
    if (!response.ok) return
    const quote = document.createElement('div')
    quote.className = 'breadcrumb'
    quote.textContent = await response.text()
    container.prepend(quote)
  } catch (_) {
    // 一言服务不可用时不影响页面业务。
  }
})()
