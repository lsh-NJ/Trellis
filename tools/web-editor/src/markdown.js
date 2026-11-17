/**
 * Markdown ↔ 编辑器 HTML 的桥。
 *
 * 为什么不用 tiptap-markdown：知识库的 Markdown 有自己的一套「注释语法」——
 * `[[原文 => 概念名]]`、`{{AI 补全内容}}`、`:::ai` 块，以及 `。。。` 槽位。
 * 这些不是 CommonMark，直接交给标准解析器会被当成普通文本；所以这里先把它们
 * 转成带 class 的 HTML（marked 允许内嵌 HTML），编辑器再按 class 建自定义 mark/node。
 * 反方向用 turndown 把 HTML 变回 Markdown，自定义 class 再还原成原始语法。
 *
 * 约定：只有「围栏代码块里」的内容原样保留；行内代码和正文里的自定义语法才处理。
 */
import { marked } from 'marked'
import TurndownService from 'turndown'
import { gfm } from 'turndown-plugin-gfm'

const AI_BLOCK_OPEN = ':::ai'
const AI_BLOCK_CLOSE = ':::'

/**
 * 「看起来一样」的语法符号：全角也认（中英文输入法一致，2026-11-16 用户要求）。
 *
 * 只在这些符号**处在语法位置**时接受全角；正文里的全角标点（「（见［1］）」）不动。
 * 手动转义写法（反斜杠夹在两个方括号之间）天然匹配不到，因为两个方括号不相邻。
 */

/** 行内概念标注 [[…]]；全角 ［［…］］（U+FF3B/U+FF3D）同样认。 */
const CONCEPT_INLINE_RE = /[\uFF3B\[]{2}([^\[\]\uFF3B\uFF3D\n]*?)[\uFF3D\]]{2}/g

/** 行内 AI 内容 {{…}}；全角 ｛｛…｝｝（U+FF5B/U+FF5D）同样认。 */
const AI_INLINE_RE = /[\uFF5B{]{2}([^{}\uFF5B\uFF5D\n]*?)[\uFF5D}]{2}/g

/** 概念标注里的映射分隔符：=>、→、⇒、全角 ＝＞ 都认（解析后统一）。 */
const CONCEPT_SEPARATOR_RE = /\s*(?:=>|→|⇒|＝＞)\s*/

/** :::ai / ::: 行：全角冒号 ：：： 同样认。 */
const AI_BLOCK_OPEN_RE = /^[：:]{3}ai$/
const AI_BLOCK_CLOSE_RE = /^[：:]{3}$/

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 块级公式 → Tiptap math 节点认的 HTML（KaTeX 在 node view 里渲染，见 main.js）。 */
function blockMathHtml(latex) {
  return '<div data-type="block-math" data-latex="' + escapeHtml(latex) + '"></div>'
}

/** 行内公式：同样走 Tiptap 的 inline-math 节点。 */
function inlineMathHtml(latex) {
  return '<span data-type="inline-math" data-latex="' + escapeHtml(latex) + '"></span>'
}

/** 行内自定义语法 → HTML。跳过行内代码（反引号里）里的内容。 */
function rewriteInline(line) {
  // 先把行内代码切出来，避免把 `[[x]]` 当成概念标注。
  const parts = []
  let rest = line
  const codeRe = /`[^`\n]*`/g
  let last = 0
  let match
  while ((match = codeRe.exec(line)) !== null) {
    parts.push({ code: false, text: line.slice(last, match.index) })
    parts.push({ code: true, text: match[0] })
    last = match.index + match[0].length
  }
  parts.push({ code: false, text: line.slice(last) })

  return parts
    .map((part) => {
      if (part.code) return part.text
      let text = part.text
      // 行内公式 $...$（先做，避免后面生成的 span 属性被误匹配）。
      // 排除「$5 和 $10」这类货币：内容两端必须是非空白，且不能全是数字/标点。
      text = text.replace(/(^|[^\\$])\$([^$\n]+?)\$/g, (all, prefix, inner) => {
        if (!/^[^\s]/.test(inner) || !/[^\s]$/.test(inner)) return all
        if (/^[\d.,\s]+$/.test(inner)) return all
        return prefix + inlineMathHtml(inner)
      })
      // [[原文 => 概念名]]：原文照显示，概念名进 data-target。
      // 全角方括号/全角映射符同样认（中英文输入法一致），输出统一成半角类名。
      text = text.replace(CONCEPT_INLINE_RE, (all, inner) => {
        const sep = CONCEPT_SEPARATOR_RE.exec(inner)
        if (sep) {
          const shown = inner.slice(0, sep.index).trim()
          const target = inner.slice(sep.index + sep[0].length).trim()
          return '<span class="concept" data-target="' + escapeHtml(target) + '">' + escapeHtml(shown) + '</span>'
        }
        return '<span class="concept">' + escapeHtml(inner.trim()) + '</span>'
      })
      // {{AI 补全内容}}（全角花括号同样认）
      text = text.replace(AI_INLINE_RE, (all, inner) => {
        return '<span class="ai">' + escapeHtml(inner) + '</span>'
      })
      return text
    })
    .join('')
}


/**
 * 行首块级标记的全角写法归一（中英文输入法一致）。
 *
 * 只认「行首 + 标记 + 空白」这种明确的块级位置：正文里的全角破折号/星号不动。
 * 返回的行直接喂给 marked；序列化时一律写半角，文件收敛成同一套符号。
 */
function normalizeBlockLine(line) {
  const lead = /^[ \t]*/.exec(line)[0]
  const rest = line.slice(lead.length)
  // 标题：＃＃ 标题
  let match = /^([＃]{1,6})([ \t　]+)/.exec(rest)
  if (match) return lead + '#'.repeat(match[1].length) + ' ' + rest.slice(match[0].length)
  // 无序列表：－ ＊ ＋
  match = /^([－＊＋])([ \t　]+)/.exec(rest)
  if (match) return lead + '-' + ' ' + rest.slice(match[0].length)
  // 引用：＞
  match = /^([＞])([ \t　]+)/.exec(rest)
  if (match) return lead + '> ' + rest.slice(match[0].length)
  // 有序列表：１２． / １２ 后跟全角点
  match = /^([0-9０-９]{1,9})([．.])([ \t　]+)/.exec(rest)
  if (match) {
    const digits = match[1].replace(/[０-９]/g, (char) => String(char.charCodeAt(0) - 0xff10))
    return lead + digits + '. ' + rest.slice(match[0].length)
  }
  return line
}

/**
 * 全角围栏/：：：ai 标记归一，并保证围栏代码块里的内容一个字符都不动。
 * 概念/AI 行内语法不在这里改（由 [rewriteInline] 的正则同时认全角）。
 */
function normalizeSyntaxLines(lines) {
  const out = []
  let inFence = false
  let inAi = false
  for (const line of lines) {
    const trimmed = line.trimStart()
    if (inFence) {
      if (/^[｀`]{3,}/.test(trimmed)) {
        inFence = false
        out.push(line.replace(/｀/g, '`'))
      } else {
        out.push(line)
      }
      continue
    }
    if (inAi) {
      if (AI_BLOCK_CLOSE_RE.test(trimmed)) {
        inAi = false
        const lead = line.slice(0, line.length - trimmed.length)
        out.push(lead + AI_BLOCK_CLOSE)
      } else {
        out.push(line)
      }
      continue
    }
    if (/^[｀`]{3,}/.test(trimmed)) {
      inFence = true
      out.push(normalizeBlockLine(line).replace(/｀/g, '`'))
      continue
    }
    if (AI_BLOCK_OPEN_RE.test(trimmed)) {
      inAi = true
      const lead = line.slice(0, line.length - trimmed.length)
      out.push(lead + AI_BLOCK_OPEN)
      continue
    }
    out.push(normalizeBlockLine(line))
  }
  return out
}

/**
 * 修正「缩进不足」的嵌套列表。
 *
 * CommonMark 要求子列表缩进到父项的内容列（`2. ` 后面要 3 列）。但很多中文笔记习惯
 * 直接敲 2 个空格，strictly 解析下来子项会变成同级列表——一进编辑器就被拍平、重新编号，
 * 用户看到的是「编辑器把我的笔记结构改了」。
 *
 * 这里按「父项内容列」把明显想嵌套的行补到正确缩进；只在围栏代码块 / :::ai 块之外动手。
 * 启发式，但比「原样交给 CommonMark 再怪用户写得不对」更符合笔记场景。
 */
function normalizeListIndent(lines) {
  const markerRe = /^([ ]*)([-*+]|\d{1,9}[.)])([ \t]+)(.*)$/
  const indentOf = (line) => (/^[ \t]*/.exec(line)[0].replace(/\t/g, '    ').length)
  const stack = [] // { itemIndent, contentIndent }
  const out = []
  let inFence = false
  let inAi = false
  for (const line of lines) {
    const trimmed = line.trimStart()
    if (trimmed.startsWith('```')) {
      inFence = !inFence
      out.push(line)
      stack.length = 0
      continue
    }
    if (inFence) {
      out.push(line)
      continue
    }
    if (trimmed === AI_BLOCK_OPEN) { inAi = true; stack.length = 0; out.push(line); continue }
    if (trimmed === AI_BLOCK_CLOSE) { inAi = false; out.push(line); continue }
    if (inAi) { out.push(line); continue }
    if (line.trim() === '') {
      out.push(line)
      continue
    }
    const indent = indentOf(line)
    const marker = markerRe.exec(line)
    if (!marker) {
      // 非列表行：缩进回到更浅的层级就说明那一层已经结束。
      while (stack.length > 0 && indent < stack[stack.length - 1].itemIndent) stack.pop()
      out.push(line)
      continue
    }
    while (stack.length > 0 && indent < stack[stack.length - 1].itemIndent) stack.pop()
    let effective = indent
    const parent = stack[stack.length - 1]
    if (parent && indent > parent.itemIndent && indent < parent.contentIndent) {
      effective = parent.contentIndent
    }
    // 同级兄弟：替换上一层，不要把兄弟堆成父子。
    if (parent && parent.itemIndent === effective) stack.pop()
    stack.push({ itemIndent: effective, contentIndent: effective + marker[2].length + marker[3].length })
    out.push(' '.repeat(effective) + line.slice(marker[1].length))
  }
  return out
}

/**
 * 序列化转义策略（2026-11-16 用户定的）。
 *
 * - **程序不主动加多余的反斜杠**：`[草稿]`、`2*3` 这类正文原样写；
 * - 只有「不转义、下次载入就会变成语法」的地方才写反斜杠：自定义方言的 `\[\[…\]\]`、
 *   `{{…}}`，以及行首会被当成标题/列表/引用的标记。这是保住字面意思的最小转义；
 * - 用户想显示字面的 `\[\[…\]\]`，保存时原样保留——保留的是用户自己敲的转义，不是程序新加的。
 */
function escapeMarkdownText(text) {
  return String(text)
    .replace(/\[\[/g, '\\[\\[')
    .replace(/\]\]/g, '\\]\\]')
    .replace(/\{\{/g, '\\{\\{')
    .replace(/\}\}/g, '\\}\\}')
    // 行首块级标记：text node 开头是这些字符时，不转义会在下次载入变成块结构。
    .replace(/^([-+>*])/, '\\$1')
    .replace(/^(#{1,6}) /, '\\$1 ')
    .replace(/^(~{3,})/, '\\$1')
    .replace(/^(=+)$/, '\\$1')
    .replace(/^(\d{1,9})\. /, '$1\\. ')
}

/**
 * 清理 turndown 留下、但语义上没必要保留的行内转义。
 *
 * turndown 是按 text node 转义的：「行内代码后面跟一个连字符」也会写成 `\-`，
 * 语义没变，但每改一个字整篇笔记就多出几十处 diff。行首的转义是语法，必须保留；
 * 行内的只留自定义方言那几种（`\[\[` 不在清理列表里）。
 */
function tidyEscapes(markdown) {
  let inFence = false
  return markdown
    .split('\n')
    .map((line) => {
      if (line.trimStart().startsWith('```')) {
        inFence = !inFence
        return line
      }
      if (inFence) return line
      // 行内代码里是原文（turndown 不转义它），拆出来跳过。
      let first = true
      return line
        .split(/(`[^`]*`)/)
        .map((part, index) => {
          if (index % 2 === 1) return part
          let body = part
          let prefix = ''
          if (first) {
            first = false
            // 行首转义是语法（列表/标题/引用/围栏），保留。
            const kept = /^([ \t]*)(\\[-+>*#]|\\~{3,}|\\=+|\\\d{1,9}\. )/.exec(body)
            if (kept) {
              prefix = kept[0]
              body = body.slice(prefix.length)
            }
          }
          // 行内过度转义：语义没变就清掉（自定义方言的 \[\[ 不在列表里，保留）。
          body = body
            .replace(/\\([-_+*>#=])/g, '$1')
            .replace(/(\d{1,9})\\. /g, '$1. ')
          return prefix + body
        })
        .join('')
    })
    .join('\n')
}
/** Markdown → HTML（给 editor.setContent 用）。 */
export function markdownToHtml(markdown) {
  const rawLines = String(markdown == null ? '' : markdown).replace(/\r\n/g, '\n').split('\n')
  const lines = normalizeListIndent(normalizeSyntaxLines(rawLines))
  const out = []
  let inFence = false
  let aiBlock = null
  let mathBlock = null
  for (const line of lines) {
    const trimmed = line.trimStart()
    if (inFence) {
      out.push(line)
      if (trimmed.startsWith('```')) inFence = false
      continue
    }
    if (aiBlock !== null) {
      if (AI_BLOCK_CLOSE_RE.test(trimmed)) {
        // 块内按普通 markdown 渲染（AI 内容里可能有段落/列表），再整体包进 ai-block。
        out.push('<div class="ai-block">' + marked.parse(aiBlock.join('\n'), { gfm: true, breaks: true }) + '</div>')
        aiBlock = null
      } else {
        aiBlock.push(line)
      }
      continue
    }
    if (trimmed.startsWith('```')) {
      inFence = true
      out.push(line)
      continue
    }
    if (AI_BLOCK_OPEN_RE.test(trimmed)) {
      aiBlock = []
      continue
    }
    // 块级公式：$$...$$ 单行，或 $$ / LaTeX 多行 / $$ 成对出现。
    if (mathBlock !== null) {
      if (trimmed === '$$') {
        out.push(blockMathHtml(mathBlock.join('\n')))
        mathBlock = null
      } else {
        mathBlock.push(line)
      }
      continue
    }
    const singleMath = /^\s*\$\$([\s\S]+?)\$\$\s*$/.exec(line)
    if (singleMath) {
      out.push(blockMathHtml(singleMath[1]))
      continue
    }
    if (trimmed === '$$') {
      mathBlock = []
      continue
    }
    out.push(rewriteInline(line))
  }
  if (aiBlock !== null) {
    // 没闭合：当普通段落处理，别把后面的内容吞掉。
    out.push('<div class="ai-block">' + marked.parse(aiBlock.join('\n'), { gfm: true, breaks: false }) + '</div>')
  }

  // breaks: true —— 笔记里的单个换行按「软换行」处理（和 Compose 编辑器一致：
  // 段内换行看得见），而不是 CommonMark 默认的「合并成一个空格」。
  let html = marked.parse(out.join('\n'), { gfm: true, breaks: true })
  return normalizeHtml(html)
}

/**
 * marked 会输出 `<li>文字</li>`/`<td>文字</td>` 这类「块里直接放行内内容」的结构，
 * 而 ProseMirror 的 listItem/tableCell 要求内容块（至少一个段落）。
 * 这里统一补一层 `<p>`，否则 setContent 会报 Invalid content。
 * 另外把 GFM 任务列表的 `<input type=checkbox>` 转成自定义 taskItem 结构。
 */
function normalizeHtml(html) {
  const box = document.createElement('div')
  box.innerHTML = html
  box.querySelectorAll('li').forEach((li) => {
    const input = li.querySelector(':scope > input[type="checkbox"]')
    if (input) {
      li.setAttribute('data-type', 'taskItem')
      li.setAttribute('data-checked', input.checked ? 'true' : 'false')
      input.remove()
      const list = li.parentElement
      if (list && !list.hasAttribute('data-type')) list.setAttribute('data-type', 'taskList')
    }
    wrapInlineChildren(li)
  })
  box.querySelectorAll('td, th').forEach((cell) => wrapInlineChildren(cell))
  return box.innerHTML
}

/** 把「没有块级子元素」的容器里的行内节点包进一个段落（列表嵌套保持在外面）。 */
function wrapInlineChildren(container) {
  if (container.querySelector(':scope > p, :scope > ul, :scope > ol, :scope > blockquote, :scope > pre, :scope > div')) {
    const stray = Array.from(container.childNodes).filter(
      (node) => node.nodeType === Node.TEXT_NODE && node.textContent.trim() !== '',
    )
    if (stray.length === 0) return
    const p = document.createElement('p')
    stray[0].parentNode.insertBefore(p, stray[0])
    stray.forEach((node) => p.appendChild(node))
    return
  }
  const p = document.createElement('p')
  while (container.firstChild) p.appendChild(container.firstChild)
  container.appendChild(p)
}

let turndown = null

function turndownService() {
  if (turndown) return turndown
  const service = new TurndownService({
    headingStyle: 'atx',
    codeBlockStyle: 'fenced',
    bulletListMarker: '-',
    emDelimiter: '*',
    strongDelimiter: '**',
    // 公式节点没有子节点（KaTeX 是 node view 画的），turndown 会把它们当「空节点」
    // 用 blankReplacement 直接输出空串。这里把空节点分流：公式按 latex 还原，其它保持空。
    blankReplacement: (content, node) => {
      const type = node.getAttribute && node.getAttribute('data-type')
      if (node.nodeName === 'DIV' && type === 'block-math') return '\n\n' + blockMathMarkdown(node) + '\n\n'
      if (node.nodeName === 'SPAN' && type === 'inline-math') return inlineMathMarkdown(node)
      return ''
    },
  })
  service.use(gfm)
  // 转义策略见 [escapeMarkdownText]：程序不再见符号就加反斜杠。
  service.escape = escapeMarkdownText

  // 删除线：gfm 插件输出单波浪线（~x~），Markdown 惯例/Compose 都是 ~~x~~。
  service.addRule('trellisStrike', {
    filter: ['s', 'del', 'strike'],
    replacement: (content) => '~~' + content + '~~',
  })

  // 列表项：缩进用 2 空格（无序）/与序号等宽（有序），跟 Compose/现有笔记的口径一致；
  // turndown 默认无序列表是 3 空格、续行 4 空格，会把笔记的列表缩进改脏。
  service.addRule('trellisListItem', {
    filter: 'li',
    replacement: (content, node, options) => {
      const parent = node.parentNode
      if (!parent) return content
      let prefix = (options.bulletListMarker || '-') + ' '
      if (parent.nodeName === 'OL') {
        const start = parent.getAttribute('start')
        const index = Array.prototype.indexOf.call(parent.children, node)
        prefix = (start ? Number(start) + index : index + 1) + '. '
      }
      const block = /\n$/.test(content)
      let body = content.replace(/^\n+/, '').replace(/\n+$/, '') + (block ? '\n' : '')
      body = body.replace(/\n/g, '\n' + ' '.repeat(prefix.length))
      return prefix + body + (node.nextSibling ? '\n' : '')
    },
  })

  // 概念标注：有 data-target 就是 [[原文 => 概念名]]
  service.addRule('trellisConcept', {
    filter: (node) => node.nodeName === 'SPAN' && node.classList && node.classList.contains('concept'),
    replacement: (content, node) => {
      const target = node.getAttribute('data-target')
      const shown = content.trim()
      return target ? '[[' + shown + ' => ' + target + ']]' : '[[' + shown + ']]'
    },
  })
  // AI 行内内容：{{…}}
  service.addRule('trellisAi', {
    filter: (node) => node.nodeName === 'SPAN' && node.classList && node.classList.contains('ai'),
    replacement: (content) => '{{' + content + '}}',
  })
  // AI 块：:::ai … :::
  service.addRule('trellisAiBlock', {
    filter: (node) => node.nodeName === 'DIV' && node.classList && node.classList.contains('ai-block'),
    replacement: (content) => {
      const inner = content.replace(/^\n+|\n+$/g, '').replace(/\n{3,}/g, '\n\n')
      return '\n\n' + AI_BLOCK_OPEN + '\n' + inner + '\n' + AI_BLOCK_CLOSE + '\n\n'
    },
  })
  // 软换行（<br>）序列化回单个换行；turndown 默认会写成「行尾两个空格」。
  service.addRule('trellisSoftBreak', {
    filter: 'br',
    replacement: () => '\n',
  })
  // 任务清单：Tiptap 的 taskItem 是 li[data-type=taskItem]（里面还包着 label/div）
  service.addRule('trellisTaskItem', {
    filter: (node) => node.nodeName === 'LI' && node.getAttribute('data-type') === 'taskItem',
    replacement: (content, node) => {
      const checked = node.getAttribute('data-checked') === 'true'
      const text = content.replace(/\n+/g, ' ').trim()
      return '- [' + (checked ? 'x' : ' ') + '] ' + text + '\n'
    },
  })
/** 块级公式节点 → Markdown（多行 LaTeX 保留换行）。 */
function blockMathMarkdown(node) {
  const latex = node.getAttribute('data-latex') || ''
  return latex.includes('\n') ? '$$\n' + latex + '\n$$' : '$$' + latex + '$$'
}

/** 行内公式节点 → Markdown。 */
function inlineMathMarkdown(node) {
  return '$' + (node.getAttribute('data-latex') || '') + '$'
}

  // 公式：块级读写 $$...$$（多行 LaTeX 保留换行），行内写 $...$。
  service.addRule('trellisBlockMath', {
    filter: (node) => node.nodeName === 'DIV' && node.getAttribute && node.getAttribute('data-type') === 'block-math',
    replacement: (content, node) => {
      const latex = node.getAttribute('data-latex') || ''
      const body = latex.includes('\n') ? '$$\n' + latex + '\n$$' : '$$' + latex + '$$'
      return '\n\n' + body + '\n\n'
    },
  })
  service.addRule('trellisInlineMath', {
    filter: (node) => node.nodeName === 'SPAN' && node.getAttribute && node.getAttribute('data-type') === 'inline-math',
    replacement: (content, node) => '$' + (node.getAttribute('data-latex') || '') + '$',
  })
  // 注意：任务项规则必须在列表项规则之后 add（turndown 后加的规则优先），
  // 否则 taskItem 会被普通 li 规则吃掉。
  turndown = service
  return service
}

/**
 * 去掉列表项 / 表格单元格里的段落外壳。
 *
 * Tiptap 的 listItem/tableCell 内容都是「块」（至少一个 <p>），turndown 对 li 里的
 * 段落会额外加空行、缩进也不对；表格单元格里带 <p> 会让 gfm 表格规则认不出来。
 * 序列化前统一拆掉这层 <p>，让 turndown 按行内内容处理。
 */
function unwrapBlockParagraphs(html) {
  if (!html.includes('<p')) return html
  const box = document.createElement('div')
  box.innerHTML = html
  // Tiptap 的表格带 <colgroup>（列宽）；turndown 的 gfm 表格规则要求
  // 「第一行就是表头行」，colgroup 挡在 tbody 前面会让它认不出来 → 整张表被 keep 成原始 HTML。
  box.querySelectorAll('table colgroup').forEach((group) => group.remove())
  box.querySelectorAll('li p, td p, th p, blockquote p, .ai-block p').forEach((paragraph) => {
    // blockquote/ai-block 里的多段落要保留（它们是内容的一部分），只拆列表项和表格单元格。
    if (paragraph.closest('li, td, th') === null && !paragraph.closest('.ai-block')) return
    const fragment = document.createDocumentFragment()
    while (paragraph.firstChild) fragment.appendChild(paragraph.firstChild)
    paragraph.parentNode.replaceChild(fragment, paragraph)
  })
  return box.innerHTML
}

/**
 * 把公式节点从 HTML 里换成占位文本，序列化后再还原。
 *
 * 为什么不直接写 turndown 规则：公式节点在 DOM 里是**没有子节点**的（KaTeX 由 node view 画），
 * turndown 会把它判成 blank node，走 blankReplacement，连带把相邻空格吃掉（实测 `$x$ 也`
 * 会变成 `$x$也`）。换成普通文本占位后，空格、列表缩进这些都按正常文本处理。
 */
function extractMathPlaceholders(html) {
  const blocks = []
  const inlines = []
  const out = String(html || '').replace(/<(div|span)([^>]*data-type="(block|inline)-math"[^>]*)>\s*<\/\1>/g, (all, tag, attrs, kind) => {
    const latexMatch = /data-latex="([^"]*)"/.exec(attrs)
    const latex = latexMatch ? decodeHtmlAttr(latexMatch[1]) : ''
    if (kind === 'block') {
      blocks.push(latex)
      return '<p>TRELLISBLOCKMATH' + (blocks.length - 1) + '</p>'
    }
    inlines.push(latex)
    return 'TRELLISINLINEMATH' + (inlines.length - 1)
  })
  return { html: out, blocks, inlines }
}

function decodeHtmlAttr(value) {
  return String(value)
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
}

function restoreMath(markdown, extracted) {
  let out = markdown
  extracted.blocks.forEach((latex, index) => {
    const body = latex.includes('\n') ? '$$\n' + latex + '\n$$' : '$$' + latex + '$$'
    out = out.split('TRELLISBLOCKMATH' + index).join(body)
  })
  extracted.inlines.forEach((latex, index) => {
    out = out.split('TRELLISINLINEMATH' + index).join('$' + latex + '$')
  })
  return out
}

/** HTML（editor.getHTML）→ Markdown。 */
export function htmlToMarkdown(html) {
  const extracted = extractMathPlaceholders(html)
  const markdown = tidyEscapes(turndownService().turndown(unwrapBlockParagraphs(extracted.html)))
  // turndown 会在块之间留空行；统一成一个空行、去掉首尾空行。
  const normalized = markdown
    .replace(/\n{3,}/g, '\n\n')
    .replace(/^\n+|\n+$/g, '')
    .replace(/[ \t]+$/gm, '')
  // 非空文档以换行结尾：和旧 bundle / 常规 Markdown 文件一致，减少「一编辑就整文件 diff」。
  return normalized ? restoreMath(normalized, extracted) + '\n' : ''
}
