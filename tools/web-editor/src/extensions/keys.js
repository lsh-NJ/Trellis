import { Extension } from '@tiptap/core'
import { TextSelection } from '@tiptap/pm/state'

/** 从选区往上找最近的 listItem；返回 { depth, pos, node } 或 null。 */
function currentListItem(state) {
  const { $from, empty } = state.selection
  if (!empty) return null
  for (let depth = $from.depth; depth >= 1; depth -= 1) {
    const node = $from.node(depth)
    if (node.type.name === 'listItem') {
      return { depth, pos: $from.before(depth), node }
    }
  }
  return null
}

/** 光标是不是在该列表项第一个文本块的最前面（段落偏移 0）。 */
function atItemStart(state, item) {
  const { $from } = state.selection
  if (item.depth > $from.depth) return false
  if (item.depth === $from.depth) return $from.parentOffset === 0
  return $from.index(item.depth) === 0 && $from.node(item.depth + 1).isTextblock && $from.parentOffset === 0
}

/** 光标（空选区）所在的标题块；不在标题里返回 null。 */
function currentHeading(state) {
  const { $from, empty } = state.selection
  if (!empty) return null
  if ($from.parent.type.name !== 'heading') return null
  return { depth: $from.depth, level: $from.parent.attrs.level }
}

/**
 * 退格处理：空列表项时删掉列表头，把它并入**上一个列表项**的末尾，作为一行续行（软换行）。
 *
 * 用户要的语义（截图）：
 *  1. 三级列表里回车多出一个空 `2.`；按一次退格 → 列表头消失，空行变成上一个列表项（1.3）里的软换行；
 *  2. 再按一次退格 → 软换行被删掉，内容并回 `1. 3` 那一行。
 * 非空列表项、或项里还有内容时不接管，交回 Tiptap 默认的 join/lift。
 */
function backspaceInList(editor) {
  const state = editor.state
  if (!state.selection.empty) return false
  const item = currentListItem(state)
  if (!item || item.depth < 2) return false
  if (item.node.textContent.trim() !== '') return false
  // 只处理「就是个空段落」的空项；如果里面还挂着子列表，别整块删掉。
  if (item.node.childCount !== 1 || !item.node.firstChild.isTextblock) return false
  if (!atItemStart(state, item)) return false
  const { $from } = state.selection
  const listDepth = item.depth - 1
  const listNode = $from.node(listDepth)
  const index = $from.index(listDepth)
  if (index === 0) return false
  const prevItemPos = $from.posAtIndex(index - 1, listDepth)
  const prevItem = state.doc.nodeAt(prevItemPos)
  if (!prevItem) return false
  // 上一个列表项最后一个文本块的结尾位置。
  let endPos = prevItemPos + 1
  prevItem.descendants((node, pos) => {
    // pos 是相对于上一个列表项**内容起点**的偏移；列表项节点本身占 1 个位置，段落再占 1 个开头位。
    if (node.isTextblock) endPos = prevItemPos + 2 + pos + node.content.size
    return true
  })
  const tr = state.tr
  if (listNode.childCount === 1) {
    const listPos = $from.before(listDepth)
    tr.delete(listPos, listPos + listNode.nodeSize)
  } else {
    tr.delete(item.pos, item.pos + item.node.nodeSize)
  }
  const hardBreak = state.schema.nodes.hardBreak.create()
  tr.insert(endPos, hardBreak)
  tr.setSelection(TextSelection.create(tr.doc, endPos + hardBreak.nodeSize))
  editor.view.dispatch(tr)
  return true
}

function enterInList(editor) {
  const state = editor.state
  if (!state.selection.empty) return false
  const item = currentListItem(state)
  if (!item) return false
  // 明确在当前层级切一个新项；不要让它落到 Tiptap 的默认分支里去升降级。
  return editor.commands.splitListItem('listItem')
}

/**
 * 标题里的退格 / 回车（2026-11-19 用户要的语义，和 Typora 一致）：
 *
 * - **退格**在标题开头（光标紧挨着显影出来的 `#`）→ 把标题降级成正文段落（标题还在；
 *   再按一次退格才和上一段合并）。以前必须把内容全删光才能变正文，很反直觉。
 * - **回车**在标题**末尾** → 下面新起一个**正文段落**（不再复制一个同级标题）；
 *   在标题中间回车 → 拆成两条同级标题（ProseMirror 默认行为）；
 *   空标题回车 → 直接变正文（否则会留一条空标题在上面）。
 */
function backspaceInHeading(editor) {
  const state = editor.state
  const heading = currentHeading(state)
  if (!heading) return false
  const { $from } = state.selection
  if ($from.parentOffset !== 0) return false
  return editor.commands.setNode('paragraph')
}

function enterInHeading(editor) {
  const state = editor.state
  const heading = currentHeading(state)
  if (!heading) return false
  const { $from } = state.selection
  const size = $from.parent.content.size
  if (size === 0) return editor.commands.setNode('paragraph')
  if ($from.parentOffset < size) return editor.commands.splitBlock()
  // 标题末尾：在它后面插一个空段落，光标落进去。
  const after = $from.after($from.depth)
  const paragraph = state.schema.nodes.paragraph.create()
  const tr = state.tr.insert(after, paragraph)
  tr.setSelection(TextSelection.create(tr.doc, after + 1))
  editor.view.dispatch(tr)
  return true
}

/**
 * 回车/退格的完整语义（键盘与 harness 的 `window.__trellisKey` 走同一套，避免两处实现漂移）：
 * - 代码块里的按键交回默认（Tab 缩进由下面的 keymap 处理）；
 * - 标题：回车在末尾新起正文段、在开头退格降级成正文（见 [enterInHeading] / [backspaceInHeading]）；
 * - 列表：回车同层级新建一项、退格空项并回上一项（见 [enterInList] / [backspaceInList]）。
 */
export function handleEnter(editor) {
  if (editor.isActive('codeBlock')) return false
  if (enterInHeading(editor)) return true
  return enterInList(editor)
}

export function handleBackspace(editor) {
  if (editor.isActive('codeBlock')) return false
  // 刚用 `## ` / `1. ` 这类输入规则变成标题/列表时，第一次退格要先撤销输入规则（Tiptap 的约定）。
  if (editor.commands.undoInputRule()) return true
  if (backspaceInHeading(editor)) return true
  return backspaceInList(editor)
}

export { enterInList, backspaceInList }

export const EditorKeys = Extension.create({
  name: 'trellisEditorKeys',
  priority: 200,
  addKeyboardShortcuts() {
    return {
      Enter: () => handleEnter(this.editor),
      Backspace: () => handleBackspace(this.editor),
      Tab: () => {
        if (!this.editor.isActive('codeBlock')) return false
        this.editor.commands.insertContent('    ')
        return true
      },
      'Shift-Tab': () => {
        if (!this.editor.isActive('codeBlock')) return false
        const { state } = this.editor
        const { $from, empty } = state.selection
        if (!empty) return false
        const before = state.doc.textBetween($from.start(), $from.pos, '\n')
        let spaces = 0
        while (spaces < 4 && before[before.length - 1 - spaces] === ' ') spaces += 1
        if (spaces === 0) return false
        this.editor.chain().deleteRange({ from: $from.pos - spaces, to: $from.pos }).run()
        return true
      },
    }
  },
})
