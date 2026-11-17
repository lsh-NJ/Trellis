import { InputRule, Node, mergeAttributes } from '@tiptap/core'
import { TextSelection } from '@tiptap/pm/state'

/**
 * 块级 AI 内容 :::ai … ::: 的 node。
 *
 * 内容按普通块渲染（段落/列表都行），整块用绿色引号包起来；序列化时还原
 * 成围栏形式。用独立 node 而不是 mark，是为了让「整块」有左右引号、也能整体删除。
 *
 * 2026-11-16：中英文输入法一致——行首敲全角 ：：：ai 也直接变成 AI 块。
 */
export const AiBlock = Node.create({
  name: 'aiBlock',
  group: 'block',
  content: 'block+',
  defining: true,
  parseHTML() {
    return [{ tag: 'div.ai-block' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { class: 'ai-block' }), 0]
  },
  addInputRules() {
    return [
      new InputRule({
        find: /^[：:]{3}ai$/,
        handler: ({ state, range }) => {
          const $from = state.doc.resolve(range.from)
          const paragraph = $from.parent
          if (!paragraph || paragraph.type.name !== 'paragraph') return null
          const start = $from.before($from.depth)
          const block = this.type.create(null, state.schema.nodes.paragraph.create())
          state.tr.replaceWith(start, start + paragraph.nodeSize, block)
          const caret = Math.min(start + 2, state.tr.doc.content.size)
          state.tr.setSelection(TextSelection.create(state.tr.doc, caret))
        },
      }),
    ]
  },
})
