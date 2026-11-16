import { Mark, mergeAttributes } from '@tiptap/core'

/**
 * 行内 AI 内容 `{{…}}` 的 mark：显示为绿字，两侧大引号由 index.html 的 ::before/::after 画。
 * 序列化时还原成 `{{…}}`（turndown 自定义规则见 markdown.js）。
 */
export const AiMark = Mark.create({
  name: 'aiInline',
  inclusive: false,
  parseHTML() {
    return [{ tag: 'span.ai' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'ai' }), 0]
  },
})
