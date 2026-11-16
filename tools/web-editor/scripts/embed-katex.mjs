/**
 * 把 KaTeX 的样式和字体打包成一个可直接内联进 `loadContent` 的 CSS。
 *
 * 为什么需要：JavaFX WebView 用 `loadContent` 加载（没有 base URL），CSS 里的
 * `url(fonts/KaTeX_*.woff2)` 解析不到文件，数学符号会掉字形。这里在构建时把 woff2
 * 读出来转成 data URI，和 KaTeX 样式一起写进 `resources/editor/katex.css`，
 * Kotlin 侧再内联进页面（见 MarkdownWebEditor.editorPage）。
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const project = resolve(here, '..')
const katexDist = join(project, 'node_modules', 'katex', 'dist')
const outFile = resolve(project, '..', '..', 'app', 'ui', 'src', 'jvmMain', 'resources', 'editor', 'katex.css')

let css = readFileSync(join(katexDist, 'katex.min.css'), 'utf8')

// 只保留 woff2（JavaFX WebKit 支持），其它格式的 src 去掉，避免 base64 体积翻倍。
css = css.replace(/url\(fonts\/([^)]+?)\.(?:woff|ttf)\)/g, (all, name) =>
  all.includes('woff2') ? all : 'url(fonts/' + name + '.woff2)',
)
css = css.replace(/url\(fonts\/([^)]+?)\.woff2\)/g, (all, name) => {
  const bytes = readFileSync(join(katexDist, 'fonts', name + '.woff2'))
  return 'url(data:font/woff2;base64,' + bytes.toString('base64') + ')'
})

writeFileSync(outFile, css)
console.log('[katex] ' + Math.round(css.length / 1024) + 'KB -> ' + outFile)
