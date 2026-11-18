import { Extension } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'

/**
 * 左侧 gutter（块类型提示）+ 标题语法显影。
 *
 * 设计口径（2026-11-19 用户定的）：
 *  - **光标不在 = 完全渲染态**：块类型提示只在光标所在块旁边出现；标题的 `#` 只在
 *    光标进入该标题时出现，焦点离开编辑器就全部消失；
 *  - **光标在语法旁边/里面时能看见原始语法**：标题挂一个不可编辑的 widget 显示 `#`×级别，
 *    用户把光标放到标题开头就能看到那个 `#`，按退格即把它「删掉」变成正文（见 keys.js）；
 *  - 正文块不画字符（`¶` 太丑），由 index.html 用 CSS 画一个小圆点；
 *  - 标题不再显示 `H1`/`H2` 徽标：`#` 已经说明级别，两个一起出现太吵。
 *
 * 用 node decoration 而不是 widget 画到左边距：列表项里 widget 的落点要跨
 * listItem→paragraph 下钻，算错就整块不显示（2026-11-06 改版的经验）。
 */
const MARKERS = {
  // 'paragraph' 只是标记名，index.html 的 CSS 会把它画成一个小圆点。
  paragraph: 'paragraph',
  bulletList: '•',
  orderedList: '☰',
  taskList: '☑',
  blockquote: '▍',
  codeBlock: '{ }',
  table: '▦',
  aiBlock: 'AI',
  horizontalRule: '—',
}

/** 块左边距上的提示；标题返回 null（改由行内 `#` 显影表达）。 */
function markerFor(node) {
  if (node.type.name === 'heading') return null
  return MARKERS[node.type.name] || MARKERS.paragraph
}

/** 标题前面的 `#`×级别；不可编辑、不占文档位置，只是给用户看的语法。 */
function headingSyntax(level) {
  const span = document.createElement('span')
  span.className = 'heading-syntax'
  span.setAttribute('contenteditable', 'false')
  span.setAttribute('aria-hidden', 'true')
  span.textContent = '#'.repeat(Math.max(1, Math.min(6, Number(level) || 1)))
  return span
}

export const Gutter = Extension.create({
  name: 'trellisGutter',
  addProseMirrorPlugins() {
    const editor = this.editor
    const key = new PluginKey('trellisGutter')

    // focus/blur 不改变文档，必须手动踢一次事务让 decorations 重新算。
    const redraw = () => {
      const view = editor.view
      if (!view || !view.docView) return
      view.dispatch(view.state.tr.setMeta(key, 'redraw'))
    }

    return [
      new Plugin({
        key,
        props: {
          handleDOMEvents: {
            focus: () => {
              window.setTimeout(redraw, 0)
              return false
            },
            blur: () => {
              window.setTimeout(redraw, 0)
              return false
            },
          },
          decorations(state) {
            // 焦点不在编辑器 = 完全渲染态：gutter 和标题 `#` 都不出现。
            if (!editor.isFocused) return null
            const { $from, empty } = state.selection
            if ($from.depth < 1) return null
            const top = $from.node(1)
            const pos = $from.before(1)
            const decorations = []
            const marker = markerFor(top)
            if (marker) {
              decorations.push(
                Decoration.node(pos, pos + top.nodeSize, {
                  class: 'gutter-current',
                  'data-marker': marker,
                }),
              )
            }
            // 标题的 `#`：光标在标题里时显影（焦点条件已在上面的 early return 处理）。
            if (empty && top.type.name === 'heading') {
              decorations.push(Decoration.widget(pos + 1, headingSyntax(top.attrs.level), { side: -1 }))
            }
            return decorations.length ? DecorationSet.create(state.doc, decorations) : null
          },
        },
      }),
    ]
  },
})
