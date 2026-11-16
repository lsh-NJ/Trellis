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
 * 编辑器自己的按键补充：
 * - 代码块里 Tab / Shift+Tab 缩进 4 空格（和 Compose 版口径一致）；
 * - 列表里 Enter = 同层级新建一项；Backspace = 空项时并回上一项（见 [backspaceInList]）；
 * - 其它 Tab/Shift+Tab 层级交给 StarterKit 的 listKeymap。
 *
 * priority 高一点，保证代码块/列表的按键先被我们截住。
 */
export { enterInList, backspaceInList }

export const EditorKeys = Extension.create({
  name: 'trellisEditorKeys',
  priority: 200,
  addKeyboardShortcuts() {
    return {
      Enter: () => {
        if (this.editor.isActive('codeBlock')) return false
        return enterInList(this.editor)
      },
      Backspace: () => {
        if (this.editor.isActive('codeBlock')) return false
        // 刚用 `1. ` 这类输入规则变成列表时，第一次退格要先撤销输入规则（Tiptap 的约定）。
        if (this.editor.commands.undoInputRule()) return true
        return backspaceInList(this.editor)
      },
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
