import { Node, mergeAttributes } from '@tiptap/core'

/**
 * 块级 AI 内容 `:::ai … :::`。
 *
 * 内容按普通块渲染（段落/列表都行），整块用绿色引号包起来；序列化时还原
 * 成围栏形式。用独立 node 而不是 mark，是为了让「整块」有左右引号、也能整体删除。
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
})
