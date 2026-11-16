/**
 * 两个浮层：斜杠菜单（`/`）和概念补全（`[[`）。
 *
 * 为什么不在 Tiptap 的 keymap 里做：补全是「就地替换一段源码」，需要在
 * keydown 里先于 ProseMirror 抢按键（↑↓/Enter/Tab/Esc）；用 document 捕获阶段的
 * 监听最直接。浮层 DOM 复用 index.html 里的 .slash-menu / .concept-menu 样式。
 */
import { slashCommands } from './slash-commands.js'

const SEPARATOR = '=>'

function textBefore(editor) {
  const { selection } = editor.state
  if (!selection.empty) return null
  const parent = selection.$from.parent
  if (!parent.isTextblock) return null
  return parent.textBetween(0, selection.$from.parentOffset, undefined, '\ufffc')
}

export function createOverlays(editor) {
  let mode = null // 'slash' | 'concept' | null
  let items = []
  let index = 0
  let from = 0 // 要替换的区间起点（/ 或 [[ 的位置）
  let to = 0 // 区间终点（当前光标）

  const popup = document.createElement('div')
  popup.className = 'slash-menu'
  popup.style.display = 'none'
  document.body.appendChild(popup)

  function close() {
    mode = null
    items = []
    index = 0
    popup.style.display = 'none'
    popup.innerHTML = ''
  }

  function place() {
    const coords = editor.view.coordsAtPos(editor.state.selection.from)
    const width = popup.offsetWidth || 240
    const left = Math.min(Math.max(8, coords.left), window.innerWidth - width - 8)
    popup.style.left = left + 'px'
    popup.style.top = coords.bottom + 6 + 'px'
  }

  function render() {
    popup.className = mode === 'concept' ? 'concept-menu' : 'slash-menu'
    popup.innerHTML = ''
    items.forEach((item, i) => {
      const row = document.createElement('div')
      row.className = (mode === 'concept' ? 'concept-item' : 'slash-item') + (i === index ? ' selected' : '')
      const label = document.createElement('span')
      label.className = mode === 'concept' ? 'concept-name' : 'slash-label'
      label.textContent = item.label
      const hint = document.createElement('span')
      hint.className = mode === 'concept' ? 'concept-hint' : 'slash-hint'
      hint.textContent = item.hint || ''
      row.appendChild(label)
      row.appendChild(hint)
      row.addEventListener('mousedown', (event) => {
        event.preventDefault()
        accept(i)
      })
      popup.appendChild(row)
    })
    popup.style.display = items.length ? 'block' : 'none'
    if (items.length) place()
  }

  function conceptNames() {
    return (Array.isArray(window.__trellisConcepts) ? window.__trellisConcepts : [])
      .map((name) => String(name || '').trim())
      .filter(Boolean)
  }

  function candidates(query) {
    const key = query.toLowerCase()
    return conceptNames()
      .filter((name) => !key || name.toLowerCase().includes(key))
      .slice(0, 8)
      .map((name) => ({ label: name, hint: '' }))
  }

  function update() {
    const text = textBefore(editor)
    if (text == null) return close()
    const cursor = editor.state.selection.from

    const slash = /(?:^|\s)\/([^\s/]*)$/.exec(text)
    if (slash) {
      const query = slash[1].toLowerCase()
      const matched = slashCommands().filter((command) => command.matches(query))
      if (!matched.length) return close()
      mode = 'slash'
      items = matched
      index = Math.min(index, items.length - 1)
      from = cursor - (text.length - slash.index)
      to = cursor
      return render()
    }

    const concept = /\[\[([^\]\n]*)$/.exec(text)
    if (concept) {
      const inside = concept[1]
      const separatorAt = Math.max(inside.lastIndexOf(SEPARATOR), inside.lastIndexOf('→'))
      const query = (separatorAt >= 0 ? inside.slice(separatorAt + 2) : inside).trim()
      const list = candidates(query)
      if (separatorAt >= 0) {
        if (!list.length) return close()
        mode = 'concept'
        items = list
      } else {
        mode = 'concept'
        items = list.concat([{ label: SEPARATOR, hint: '映射到概念名', separator: true }])
      }
      index = Math.min(index, items.length - 1)
      from = cursor - (text.length - concept.index)
      to = cursor
      return render()
    }
    return close()
  }

  function accept(i) {
    const item = items[Math.max(0, Math.min(i, items.length - 1))]
    if (!item) return

    if (mode === 'slash') {
      close()
      editor
        .chain()
        .focus()
        .deleteRange({ from, to })
        .run()
      item.run(editor)
      return
    }

    if (item.separator) {
      // 只补「 => 」，让用户继续选概念名；浮层别关。
      editor.chain().focus().insertContent(' ' + SEPARATOR + ' ').run()
      update()
      return
    }

    const inside = editor.state.doc.textBetween(from, to, '\n')
    const separatorAt = Math.max(inside.lastIndexOf(SEPARATOR), inside.lastIndexOf('→'))
    const shown = (separatorAt >= 0 ? inside.slice(0, separatorAt) : inside).trim()
    const display = separatorAt >= 0 ? shown || item.label : item.label
    const target = separatorAt >= 0 ? item.label : null
    close()
    editor
      .chain()
      .focus()
      .deleteRange({ from, to })
      .insertContent(display)
      .run()
    const end = from + display.length
    if (display) {
      editor
        .chain()
        .setTextSelection({ from, to: end })
        .setMark('concept', target ? { target } : {})
        .setTextSelection(end)
        .unsetMark('concept')
        .run()
    }
  }

  function handleKeyDown(event) {
    if (!mode) return false
    if (event.key === 'Escape') {
      close()
      return true
    }
    if (event.key === 'ArrowDown') {
      index = (index + 1) % items.length
      render()
      return true
    }
    if (event.key === 'ArrowUp') {
      index = (index - 1 + items.length) % items.length
      render()
      return true
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      accept(index)
      return true
    }
    return false
  }

  return { update, handleKeyDown, close }
}
