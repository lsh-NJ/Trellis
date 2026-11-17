import { Extension, textblockTypeInputRule, wrappingInputRule } from '@tiptap/core'

/**
 * 中文输入法下打出来的全角块级标记，效果与半角一致（2026-11-16 用户要求）。
 *
 * 只在「行首标记 + 空白」这种明确的块级位置转换；正文里的全角标点（－、＊、＞）不动。
 * 序列化出来的永远是半角，文件收敛成同一套符号。
 */
export const FullwidthInput = Extension.create({
  name: 'fullwidthInput',
  addInputRules() {
    const nodes = this.editor.schema.nodes
    return [
      // ＃＃ 标题
      textblockTypeInputRule({
        find: /^([＃]{1,6})[ \t　]$/,
        type: nodes.heading,
        getAttributes: (match) => ({ level: match[1].length }),
      }),
      // － ＊ ＋ 无序列表
      wrappingInputRule({ find: /^[－＊＋][ \t　]$/, type: nodes.bulletList }),
      // ＞ 引用
      wrappingInputRule({ find: /^＞[ \t　]$/, type: nodes.blockquote }),
      // １２． 有序列表
      wrappingInputRule({
        find: /^([0-9０-９]{1,9})[．.][ \t　]$/,
        type: nodes.orderedList,
        getAttributes: (match) => ({
          start: Number(match[1].replace(/[０-９]/g, (char) => String(char.charCodeAt(0) - 0xff10))),
        }),
      }),
      // ｀｀｀ 代码块
      textblockTypeInputRule({ find: /^｀｀｀$/, type: nodes.codeBlock }),
    ]
  },
})
