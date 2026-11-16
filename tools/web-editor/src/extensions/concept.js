import { InputRule, Mark, mergeAttributes } from '@tiptap/core'

/**
 * 概念标注 `[[原文 => 概念名]]` 的 mark。
 *
 * 原文照常显示（只有 `[[`/`]]` 和 `=> 概念名` 是不显示的语法），所以 mark 的文本
 * 就是「原文」；映射到的概念名放在 `data-target` 属性里，序列化时拼回 `[[原文 => 概念名]]`。
 * 没有 target 的 `[[概念]]` 只加样式，序列化成 `[[概念]]`。
 */
export const ConceptMark = Mark.create({
  name: 'concept',
  inclusive: false,
  addAttributes() {
    return {
      target: {
        default: null,
        parseHTML: (element) => element.getAttribute('data-target'),
        renderHTML: (attributes) => (attributes.target ? { 'data-target': attributes.target } : {}),
      },
    }
  },
  parseHTML() {
    return [{ tag: 'span.concept' }]
  },
  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { class: 'concept' }), 0]
  },
  addInputRules() {
    // 直接敲 `[[概念]]` / `[[原文 => 概念名]]` 时立刻变成概念标注：
    // 不然它只是普通文本，保存时会被当成普通方括号转义（旧 bug 的来源），
    // 而且用户看不到「标注生效了」的绿色下划线。
    return [
      // 手动转义：`\[\[字面\]\]` → 普通文本「[[字面]]」（不加概念 mark，也不会被当成标注）。
      // 保存时 turndown 会把普通文本里的方括号再转义回 `\[\[…\]\]`，文件语义保持一致。
      new InputRule({
        find: /\\\[\\\[([^\[\]\n]+?)\\\]\\\]$/,
        handler: ({ state, range, match }) => {
          const inner = (match[1] || '').trim()
          if (!inner) return null
          state.tr.replaceWith(range.from, range.to, state.schema.text('[[' + inner + ']]'))
        },
      }),
      new InputRule({
        find: /\[\[([^\[\]\n]+?)\]\]$/,
        handler: ({ state, range, match }) => {
          const inner = (match[1] || '').trim()
          if (!inner) return null
          const separator = inner.indexOf('=>')
          const display = (separator >= 0 ? inner.slice(0, separator) : inner).trim()
          const target = separator >= 0 ? inner.slice(separator + 2).trim() : null
          if (!display) return null
          const mark = this.type.create(target ? { target } : {})
          state.tr.replaceWith(range.from, range.to, state.schema.text(display, [mark]))
        },
      }),
    ]
  },
})
