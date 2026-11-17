import { InputRule, Mark, mergeAttributes } from '@tiptap/core'

/**
 * 行内 AI 内容 {{…}} 的 mark：显示为绿字，两侧大引号由 index.html 的 ::before/::after 画。
 * 序列化时还原成 {{…}}（turndown 自定义规则见 markdown.js）。
 *
 * 2026-11-16：中英文输入法一致——全角 ｛｛…｝｝ 同样认，输出统一成半角。
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
  addInputRules() {
    return [
      new InputRule({
        find: /[{\uFF5B]{2}([^{}\uFF5B\uFF5D\n]+?)[}\uFF5D]{2}$/,
        handler: ({ state, range, match }) => {
          const inner = (match[1] || '').trim()
          if (!inner) return null
          const mark = this.type.create()
          state.tr.replaceWith(range.from, range.to, state.schema.text(inner, [mark]))
        },
      }),
    ]
  },
})
