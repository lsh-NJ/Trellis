/**
 * Trellis 知识库 WebView 编辑器的入口（Tiptap 3 + ProseMirror）。
 *
 * 这个文件替换原来的匿名构建产物后，仍然是「一个 IIFE + index.html 内联」的加载方式，
 * 对 Kotlin 侧（MarkdownWebEditor.kt）完全透明：同一个 `<div id="editor">`、同一组
 * window API、同一套 trellisEditorBridge 回调。
 *
 * 与 Compose 版的分工：Compose 负责「功能最全」的单文本编辑；这里负责「段落式」显示
 * （块级 DOM + CSS margin，段落之间没有空行），并逐步补齐代码高亮/表格/公式/AI 补全。
 */
import { Editor } from '@tiptap/core'
import { EditorState } from '@tiptap/pm/state'
import StarterKit from '@tiptap/starter-kit'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import { Table, TableRow, TableCell, TableHeader } from '@tiptap/extension-table'
import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight'
import Mathematics from '@tiptap/extension-mathematics'
import { common, createLowlight } from 'lowlight'

// 代码块语法高亮：lowlight 自带常用语言（js/ts/python/kotlin 走 highlight.js 的 common 集）。
const lowlight = createLowlight(common)
import { ConceptMark } from './extensions/concept.js'
import { AiMark } from './extensions/ai.js'
import { AiBlock } from './extensions/ai-block.js'
import { Gutter } from './extensions/gutter.js'
import { EditorKeys, backspaceInList, enterInList } from './extensions/keys.js'
import { FullwidthInput } from './extensions/fullwidth.js'
import { createOverlays } from './ui/overlays.js'
import { markdownToHtml, htmlToMarkdown } from './markdown.js'
import { applyTheme } from './theme.js'

const element = document.getElementById('editor')
if (!element) {
  throw new Error('Trellis 编辑器挂载失败：#editor 不存在')
}

let suppress = false
let overlays = null
let updateTimer = null

const bridge = () => window.trellisEditorBridge

const editor = new Editor({
  element,
  extensions: [
    StarterKit.configure({
      heading: { levels: [1, 2, 3, 4, 5, 6] },
      // 代码块换成 lowlight 版本才有语法高亮；原来的 codeBlock 关掉避免重复注册。
      codeBlock: false,
    }),
    CodeBlockLowlight.configure({ lowlight }),
    Mathematics.configure({ katexOptions: { throwOnError: false, strict: 'ignore' } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Table.configure({ resizable: false }),
    TableRow,
    TableCell,
    TableHeader,
    ConceptMark,
    AiMark,
    AiBlock,
    Gutter,
    EditorKeys,
    FullwidthInput,
  ],
  content: '<p></p>',
  editorProps: {
    attributes: { class: 'trellis-editor', spellcheck: 'false' },
  },
  onFocus: () => {
    try { bridge()?.onFocus?.() } catch (error) { /* 桥没装好时忽略 */ }
  },
  onBlur: () => {
    try { bridge()?.onBlur?.() } catch (error) { /* 同上 */ }
  },
  onUpdate: () => {
    overlays?.update?.()
    scheduleUpdate()
  },
  onSelectionUpdate: () => overlays?.update?.(),
})

overlays = createOverlays(editor)

function notifyMarkdown() {
  if (suppress) return
  try {
    bridge()?.onUpdate?.(htmlToMarkdown(editor.getHTML()))
  } catch (error) {
    console.error('Trellis: onUpdate 失败', error)
  }
}

function scheduleUpdate() {
  if (updateTimer) clearTimeout(updateTimer)
  updateTimer = setTimeout(notifyMarkdown, 200)
}

// 页面级快捷键：WebView 有焦点时 Compose 收不到键，必须由页面 capture 后转给桥。
// macOS 的 Cmd 组合键在 JavaFX WebView 里 key 可能为空串，用 keyCode 兜底。
const SHORTCUT_KEYCODES = { 83: 'save', 78: 'newNote', 191: 'menu', 111: 'menu' }
document.addEventListener(
  'keydown',
  (event) => {
    if (overlays && overlays.handleKeyDown(event)) {
      event.preventDefault()
      event.stopPropagation()
      return
    }
    const modifier = event.metaKey || event.ctrlKey
    if (!modifier) return
    const byKey = { s: 'save', n: 'newNote', '/': 'menu', '?': 'menu' }
    const command = byKey[String(event.key || '').toLowerCase()] || SHORTCUT_KEYCODES[event.keyCode]
    if (!command) return
    event.preventDefault()
    event.stopPropagation()
    try {
      bridge()?.onShortcut?.(command)
    } catch (error) {
      console.error('Trellis: onShortcut 失败', error)
    }
  },
  true,
)

/** markdown 文本偏移 → ProseMirror 文档位置（近似：块之间按空行算两个换行）。 */
function positionForTextOffset(offset) {
  const doc = editor.state.doc
  let remaining = Math.max(0, offset)
  let result = null
  doc.descendants((node, pos) => {
    if (result != null) return false
    if (node.isText) {
      if (remaining <= node.text.length) {
        result = pos + remaining
        return false
      }
      remaining -= node.text.length
      return true
    }
    if (node.isBlock && node.type.name !== 'doc' && node.type.name !== 'text') {
      if (remaining >= 2) {
        remaining -= 2
      } else {
        remaining = 0
      }
    }
    return true
  })
  return result == null ? doc.content.size : result
}

/**
 * 清空撤销历史。
 *
 * setContent 会把「灌入整篇」写进 ProseMirror 的 history：切换笔记后按 Cmd+Z
 * 有可能把上一篇的内容撤回到当前笔记里（旧 bundle 实测有这个风险）。换笔记是
 * 「打开另一份文档」而不是「编辑动作」，所以灌完内容直接重建一次 EditorState，
 * 让 history 插件回到空状态。
 */
function resetHistory() {
  const state = editor.state
  editor.view.updateState(
    EditorState.create({ schema: state.schema, doc: state.doc, plugins: state.plugins }),
  )
}

/**
 * 把滚动位置真的送回顶部。
 *
 * 页面里滚动的是 `#editor`（overflow-y: auto），不是 window；`window.scrollTo` 对它无效。
 * 以前只调 window.scrollTo，ProseMirror 灌内容时把选区滚进视野留下的 scrollTop
 * 就一直留着——表现是「刚打开笔记，第一行标题被上方遮掉一截」。
 * 用 rAF + 兜底 setTimeout 各归零一次：WebKit 排版完成前设 scrollTop 可能被回滚。
 */
function resetScroll() {
  const toTop = () => {
    element.scrollTop = 0
    if (element.parentElement) element.parentElement.scrollTop = 0
    if (document.scrollingElement) document.scrollingElement.scrollTop = 0
    window.scrollTo(0, 0)
  }
  toTop()
  const raf = window.requestAnimationFrame || ((fn) => window.setTimeout(fn, 0))
  raf(toTop)
  window.setTimeout(toTop, 120)
}

window.setMarkdown = (markdown) => {
  suppress = true
  try {
    const html = markdownToHtml(markdown)
    editor.commands.setContent(html, { emitUpdate: false })
    resetHistory()
    overlays?.close?.()
    resetScroll()
    return true
  } finally {
    suppress = false
  }
}

window.getMarkdown = () => htmlToMarkdown(editor.getHTML())
window.getHTML = () => editor.getHTML()
window.setTheme = (theme) => {
  applyTheme(theme)
  return true
}
/** 页面里的「菜单」浮层：Compose 的浮层会被重量级 JFXPanel 盖住，所以在 DOM 里画。 */
let shortcutMenu = null

function closeShortcutMenu(notify) {
  if (!shortcutMenu) return
  document.removeEventListener('keydown', shortcutMenu.onKeyDown, true)
  shortcutMenu.el.remove()
  shortcutMenu = null
  if (notify) {
    try { bridge()?.onMenuClosed?.() } catch (error) { /* 桥没装好时忽略 */ }
  }
}

/** 光标（或选区）在视口里的位置；拿不到就退回编辑器左上角附近。 */
function caretAnchor() {
  try {
    const selection = window.getSelection()
    if (selection && selection.rangeCount > 0) {
      const range = selection.getRangeAt(0)
      if (element.contains(range.commonAncestorContainer)) {
        const rect = range.getBoundingClientRect()
        if (rect && (rect.left || rect.top)) return { x: rect.left, y: rect.bottom }
      }
    }
  } catch (error) { /* 选区异常时退回默认位置 */ }
  const box = element.getBoundingClientRect()
  return { x: box.left + 40, y: box.top + 48 }
}

window.showShortcutMenu = (sections) => {
  closeShortcutMenu(false)
  const scrim = document.createElement('div')
  scrim.className = 'shortcut-scrim'
  const panel = document.createElement('aside')
  panel.className = 'shortcut-panel'

  const header = document.createElement('div')
  header.className = 'shortcut-header'
  const title = document.createElement('span')
  title.className = 'shortcut-title'
  title.textContent = '菜单'
  const hint = document.createElement('span')
  hint.className = 'shortcut-hint'
  hint.textContent = '点空白处关闭'
  header.appendChild(title)
  header.appendChild(hint)
  panel.appendChild(header)

  const list = document.createElement('div')
  list.className = 'shortcut-list'
  for (const section of Array.isArray(sections) ? sections : []) {
    const sectionTitle = document.createElement('div')
    sectionTitle.className = 'shortcut-section'
    sectionTitle.textContent = section.title || ''
    list.appendChild(sectionTitle)
    for (const item of section.items || []) {
      const row = document.createElement('div')
      row.className = 'shortcut-row'
      const action = document.createElement('span')
      action.className = 'shortcut-action'
      action.textContent = item.action || ''
      const keys = document.createElement('kbd')
      keys.className = 'shortcut-keys'
      keys.textContent = item.keys || ''
      row.appendChild(action)
      row.appendChild(keys)
      list.appendChild(row)
    }
  }
  panel.appendChild(list)
  scrim.appendChild(panel)

  // 浮窗贴在光标旁边（和斜杠菜单一致）；先以 hidden 挂上去量尺寸，再夹到视口内。
  panel.style.visibility = 'hidden'
  document.body.appendChild(scrim)
  const anchor = caretAnchor()
  const width = panel.offsetWidth
  const height = panel.offsetHeight
  const margin = 12
  const maxLeft = Math.max(margin, window.innerWidth - width - margin)
  const left = Math.min(Math.max(margin, anchor.x), maxLeft)
  let top = anchor.y + 6
  if (top + height > window.innerHeight - margin) {
    top = Math.max(margin, anchor.y - height - 6)
  }
  panel.style.left = left + 'px'
  panel.style.top = top + 'px'
  panel.style.visibility = 'visible'

  const onKeyDown = (event) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      closeShortcutMenu(true)
    }
  }
  scrim.addEventListener('mousedown', (event) => {
    if (event.target === scrim) closeShortcutMenu(true)
  })
  document.addEventListener('keydown', onKeyDown, true)
  shortcutMenu = { el: scrim, onKeyDown }
  return true
}

window.hideShortcutMenu = () => {
  closeShortcutMenu(false)
  return true
}

window.setConcepts = (names) => {
  window.__trellisConcepts = Array.isArray(names) ? names.slice() : []
  return true
}
// 调试/回归用：直接跑列表按键处理逻辑（不经过真实键盘事件，JavaFX 的合成事件不可靠）。
window.__trellisKey = (name) => {
  if (name === 'Enter') return enterInList(editor)
  if (name === 'Backspace') return backspaceInList(editor)
  return false
}
window.focusEditor = () => editor.commands.focus()
window.revealRange = (start, end) => {
  const from = positionForTextOffset(start)
  const to = Math.max(from, positionForTextOffset(end))
  editor.chain().focus().setTextSelection({ from, to }).run()
  editor.view.dispatch(editor.state.tr.scrollIntoView())
  return true
}
// 测试/调试用：harness 可以直接构造编辑操作；宿主不依赖它。
window.__trellisEditor = editor
// 旧 bundle 暴露过这个名字给 Tiptap 的键盘转发用；保留空实现避免调用方踩空。
window.__trellisHandleKey = () => false
window.editorReady = true
