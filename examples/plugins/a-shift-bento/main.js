// 值班台 / BENTO SHIFT —— 把 dashboard-set/a-shift-bento.html 的配色做成一个主题包。
//
// 这个插件交配色 token + 一点外壳基因（圆角、字族），不交卡片；装上之后它是「主题列表里的一项」。
// 样片里其余的身份（等宽 kicker、大号数字、硬阴影、12 栏 bento 排布）
// 属于宿主画法：有的是 UI 词汇表（text 的 font/size/weight、grid、table、
// sparkline、bars、ring），有的是界面偏好（card = "bento"、nav = "left"）。

trellis.register({
  theme: {
    id: 'bento',
    name: '值班台',
    description: '纸感米白 / 荧光青柠 / 墨黑；编辑风、硬边小圆角',
    light: {
      primary: '#141414',
      onPrimary: '#FFFDF7',
      primaryContainer: '#D6F24B',
      onPrimaryContainer: '#141414',
      secondary: '#3A5BE0',
      onSecondary: '#FFFFFF',
      secondaryContainer: '#E8E4D6',
      onSecondaryContainer: '#141414',
      tertiary: '#4B6B00',
      onTertiary: '#FFFFFF',
      background: '#F7F5EE',
      onBackground: '#141414',
      surface: '#FFFDF7',
      onSurface: '#141414',
      surfaceVariant: '#EFEBDD',
      onSurfaceVariant: '#4B473C',
      outline: '#B9B3A2',
      outlineVariant: '#E2DED2',
      error: '#B3261E',
      onError: '#FFFFFF'
    },
    dark: {
      primary: '#D6F24B',
      onPrimary: '#141414',
      primaryContainer: '#2A2E12',
      onPrimaryContainer: '#EAF7B8',
      secondary: '#A9B8FF',
      onSecondary: '#141414',
      secondaryContainer: '#23252E',
      onSecondaryContainer: '#DCE1FF',
      tertiary: '#D6F24B',
      onTertiary: '#141414',
      background: '#141414',
      onBackground: '#F7F5EE',
      surface: '#1C1B19',
      onSurface: '#F7F5EE',
      surfaceVariant: '#262521',
      onSurfaceVariant: '#C9C4B4',
      outline: '#6C685D',
      outlineVariant: '#3A3832',
      error: '#F2B8B5',
      onError: '#2A1110'
    },
    // 外壳基因：样片是 3px 小圆角、等宽小标、编辑风。
    // 只开放这几项（宿主有词表和范围校验），不是 CSS。
    shape: {
      cardRadius: '3',
      controlRadius: '3'
    },
    type: {
      labelFamily: 'mono',
      titleFamily: 'default',
      displayFamily: 'condensed',
      scale: '1'
    },
    // 颜色分工：数据用靛、状态用砖红/琥珀、强调色上的文字用 lime-d。
    // 这一组不进 light/dark，是「岗位色」——绘图只许取 data 系，否则又会变成单色页。
    extra: {
      data: '#2C4A63',
      dataMuted: '#5E7891',
      dataTrack: '#E2DED2',
      accentText: '#4F6A12',
      danger: '#B23A2E',
      dangerContainer: '#F7E4DF',
      warning: '#8F5C12',
      warningContainer: '#F6EBD6',
      // 青柠只做底/完成：满格的条、满环、已完成行用它，不做线条。
      success: '#D6F24B',
      successContainer: '#EEF7C4'
    }
  }
});

// 样片里的数据小部件映射到宿主词汇表（给 agent 当模板用，不参与渲染）：
//   KPI 大数字      -> stat + text(size: 'huge', weight: 'bold')
//   等宽 kicker     -> text(size: 'small', font: 'mono')
//   走势/迷你折线   -> sparkline
//   横向条形        -> bars
//   环形完成度      -> ring
//   明细表          -> table（headers / rows / caption）
//   标签/旗标       -> badge(tone: 'success' | 'danger' | 'muted')
//   多栏 bento      -> grid(columns: 2..4) + row
// 样片里刻意不做的：像素画布、任意 SVG、自定义颜色/CSS —— 那是宿主封闭词表之外的东西。
