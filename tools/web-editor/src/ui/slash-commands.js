/**
 * 斜杠菜单的命令表。
 *
 * 与 Compose 版 markdownCommands 保持同一套动作（标题/列表/引用/代码/分割线/行内样式/表格），
 * 但 WebView 里直接调 Tiptap 命令，不经过「文本 + 选区 → 文本」的纯函数。
 * matches 只给菜单过滤用：id、中文标签、常见拼音都能命中。
 */
const COMMANDS = [
  { id: 'h1', label: '一级标题', hint: '# 大标题', keywords: 'h1 biaoti bt title heading', run: (editor) => editor.chain().focus().toggleHeading({ level: 1 }).run() },
  { id: 'h2', label: '二级标题', hint: '## 小节', keywords: 'h2 biaoti bt heading', run: (editor) => editor.chain().focus().toggleHeading({ level: 2 }).run() },
  { id: 'h3', label: '三级标题', hint: '### 更小的节', keywords: 'h3 biaoti bt heading', run: (editor) => editor.chain().focus().toggleHeading({ level: 3 }).run() },
  { id: 'paragraph', label: '正文', hint: '去掉标题层级', keywords: 'zhengwen zw text paragraph', run: (editor) => editor.chain().focus().setParagraph().run() },
  { id: 'bullet', label: '无序列表', hint: '- 一项', keywords: 'liebiao lb list bullet wuxu', run: (editor) => editor.chain().focus().toggleBulletList().run() },
  { id: 'ordered', label: '有序列表', hint: '1. 第一步', keywords: 'liebiao lb list number youxu bianhao', run: (editor) => editor.chain().focus().toggleOrderedList().run() },
  { id: 'task', label: '任务清单', hint: '- [ ] 待办', keywords: 'renwu rw todo task qingdan checkbox', run: (editor) => editor.chain().focus().toggleTaskList().run() },
  { id: 'quote', label: '引用', hint: '> 引用一段', keywords: 'yinyong yy quote blockquote', run: (editor) => editor.chain().focus().toggleBlockquote().run() },
  { id: 'codeblock', label: '代码块', hint: '``` 围栏', keywords: 'daima dm code fence codeblock', run: (editor) => editor.chain().focus().toggleCodeBlock().run() },
  { id: 'rule', label: '分割线', hint: '--- 横向分隔', keywords: 'fenge fg rule hr divider hengxian', run: (editor) => editor.chain().focus().setHorizontalRule().run() },
  { id: 'table', label: '表格', hint: '2×2 空表', keywords: 'biaoge bg table', run: (editor) => editor.chain().focus().insertTable({ rows: 2, cols: 2, withHeaderRow: true }).run() },
  { id: 'bold', label: '粗体', hint: '**加粗**', keywords: 'cuti ct bold strong b', run: (editor) => editor.chain().focus().toggleBold().run() },
  { id: 'italic', label: '斜体', hint: '*斜体*', keywords: 'xieti xt italic em i', run: (editor) => editor.chain().focus().toggleItalic().run() },
  { id: 'strike', label: '删除线', hint: '~~删掉~~', keywords: 'shanchuxian scx strike del', run: (editor) => editor.chain().focus().toggleStrike().run() },
  { id: 'code', label: '行内代码', hint: '`代码`', keywords: 'daima dm code inline', run: (editor) => editor.chain().focus().toggleCode().run() },
]

export function slashCommands() {
  return COMMANDS.map((command) => ({
    ...command,
    matches(query) {
      if (!query) return true
      return (command.id + ' ' + command.label + ' ' + command.keywords).toLowerCase().includes(query)
    },
  }))
}
