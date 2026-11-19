/**
 * 把 Compose 主题灌进页面：只改 CSS 变量与 body 颜色，不碰编辑器结构。
 *
 * 字段与 Kotlin 侧 WebTheme 一一对应（background/onBackground/onSurfaceVariant/accent/
 * accentSoft/codeBackground/border）。默认值是浅色书桌主题，和 index.html 的 :root 一致。
 *
 * 2026-11-19：链接色不再吃浏览器默认蓝，而是按背景深浅算（浅色 #2563eb / 深色 #7cb0ff）。
 * 链接前那颗地球是 emoji（见 index.html）：JavaFX WebView 不加载 data: URI 图片，
 * emoji 自带颜色、不用跟着主题换，所以这里只换文字色。
 */
function parseColor(value) {
  const text = String(value || '').trim()
  let match = /^#([0-9a-fA-F]{3,8})$/.exec(text)
  if (match) {
    let hex = match[1]
    if (hex.length === 3 || hex.length === 4) {
      hex = hex
        .split('')
        .map((char) => char + char)
        .join('')
    }
    const r = parseInt(hex.slice(0, 2), 16)
    const g = parseInt(hex.slice(2, 4), 16)
    const b = parseInt(hex.slice(4, 6), 16)
    return { r: r, g: g, b: b }
  }
  match = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i.exec(text)
  if (match) {
    return { r: Number(match[1]), g: Number(match[2]), b: Number(match[3]) }
  }
  return null
}

/** 背景算不算深色（相对亮度阈值按 sRGB 亮度粗算，够用）。 */
function isDarkBackground(value) {
  const color = parseColor(value)
  if (!color) return false
  const luminance = 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b
  return luminance < 140
}

const LIGHT_LINK = '#2563eb'
const DARK_LINK = '#7cb0ff'

export function applyTheme(theme) {
  if (!theme) return
  const root = document.documentElement
  const vars = {
    '--bg': theme.background,
    '--fg': theme.onBackground,
    '--fg-soft': theme.onSurfaceVariant,
    '--accent': theme.accent,
    '--accent-soft': theme.accentSoft,
    '--code-bg': theme.codeBackground,
    '--border': theme.border,
  }
  for (const [name, value] of Object.entries(vars)) {
    if (value) root.style.setProperty(name, value)
  }
  root.style.setProperty('--link', isDarkBackground(theme.background) ? DARK_LINK : LIGHT_LINK)
  if (theme.background) document.body.style.background = theme.background
  if (theme.onBackground) document.body.style.color = theme.onBackground
}
