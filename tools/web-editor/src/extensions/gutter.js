import { Extension } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'

/**
 * 左侧 gutter：只给光标所在的**顶层块**挂一个 `gutter-current` + `data-marker`，
 * 由 index.html 的 `.gutter-current::before` 绝对定位画到左边距（不占行高、不影响段距）。
 *
 * 用 node decoration 而不是 widget：列表项里 widget 的落点要跨 listItem→paragraph 下钻，
 * 算错就整块不显示（2026-11-06 改版的经验）。
 */
const MARKERS = {
  paragraph: '¶',
  bulletList: '•',
  orderedList: '☰',
  taskList: '☑',
  blockquote: '▍',
  codeBlock: '{ }',
  table: '▦',
  aiBlock: 'AI',
  horizontalRule: '—',
}

function markerFor(node) {
  if (node.type.name === 'heading') return 'H' + node.attrs.level
  return MARKERS[node.type.name] || '¶'
}

export const Gutter = Extension.create({
  name: 'trellisGutter',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('trellisGutter'),
        props: {
          decorations(state) {
            const { $from } = state.selection
            if ($from.depth < 1) return null
            const top = $from.node(1)
            const pos = $from.before(1)
            const marker = markerFor(top)
            if (!marker) return null
            return DecorationSet.create(state.doc, [
              Decoration.node(pos, pos + top.nodeSize, {
                class: 'gutter-current',
                'data-marker': marker,
              }),
            ])
          },
        },
      }),
    ]
  },
})
