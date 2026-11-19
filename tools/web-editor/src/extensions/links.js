import { Extension } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'

/**
 * 链接点击 → 宿主（Compose）→ 系统默认浏览器。
 *
 * 为什么必须自己接管：JavaFX WebView 里点 <a href> 默认是**把整个编辑器页面导航过去**
 * （WebView 没有地址栏，一导航编辑器就没了）；Tiptap 的 Link 默认 openOnClick 又是
 * window.open，WebView 直接丢弃。所以这里一律 preventDefault，再按协议决定是否交给宿主。
 *
 * 交互（2026-11-19 用户要的）：
 *  - 点链接 → 默认浏览器打开；
 *  - **按住 Option/Alt 再点**（或光标已经在同一条链接里）→ 不打开，正常落光标，方便改文字；
 *  - 按下后拖了超过 4px（在选字）→ 不算点击，交回默认行为；
 *  - 只放行 http / https / mailto，file: / javascript: / data: 之类只吞掉、不打开。
 */
const SAFE_SCHEMES = ['http', 'https', 'mailto']

function schemeOf(url) {
  const match = /^\s*([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(String(url || ''))
  return match ? match[1].toLowerCase() : ''
}

export function isSafeLink(url) {
  return SAFE_SCHEMES.indexOf(schemeOf(url)) >= 0
}

/** 交给宿主用默认浏览器打开；宿主没装桥（harness 未设桩）就静默忽略。 */
export function openLinkInHost(url) {
  if (!isSafeLink(url)) return false
  const bridge = window.trellisEditorBridge
  if (!bridge || typeof bridge.onLinkClick !== 'function') return false
  try {
    bridge.onLinkClick(url)
  } catch (error) {
    console.error('Trellis: onLinkClick 失败', error)
  }
  return true
}

function anchorOf(event) {
  const target = event.target
  if (!target || typeof target.closest !== 'function') return null
  return target.closest('a[href]')
}

/** 光标已经在同一条链接里（键盘挪进去的）→ 这次点击只落光标。 */
function sameLinkSelected(editor, href) {
  if (!editor.isActive('link')) return false
  return editor.getAttributes('link').href === href
}

export const LinkClick = Extension.create({
  name: 'trellisLinkClick',
  priority: 150,
  addProseMirrorPlugins() {
    const editor = this.editor
    let downAt = null
    return [
      new Plugin({
        key: new PluginKey('trellisLinkClick'),
        props: {
          handleDOMEvents: {
            mousedown: (view, event) => {
              downAt = { x: event.clientX, y: event.clientY }
              return false
            },
          },
          handleClick(view, pos, event) {
            const anchor = anchorOf(event)
            if (!anchor) return false
            // 编辑器里的链接永远不该让 WebView 自己导航。
            event.preventDefault()
            event.stopPropagation()
            const href = anchor.getAttribute('href') || ''
            if (!isSafeLink(href)) return false
            // 按下后移动过（在拖选）→ 不打开。
            if (downAt && (Math.abs(event.clientX - downAt.x) > 4 || Math.abs(event.clientY - downAt.y) > 4)) {
              return false
            }
            // Option/Alt 点击、或光标已经在这条链接里 → 只是编辑。
            if (event.altKey || sameLinkSelected(editor, href)) return false
            openLinkInHost(href)
            return true
          },
        },
      }),
    ]
  },
})
