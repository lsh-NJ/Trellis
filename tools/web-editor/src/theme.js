/**
 * 把 Compose 主题灌进页面：只改 CSS 变量与 body 颜色，不碰编辑器结构。
 *
 * 字段与 Kotlin 侧 WebTheme 一一对应（background/onBackground/onSurfaceVariant/accent/
 * accentSoft/codeBackground/border）。默认值是浅色书桌主题，和 index.html 的 :root 一致。
 */
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
  if (theme.background) document.body.style.background = theme.background
  if (theme.onBackground) document.body.style.color = theme.onBackground
}
