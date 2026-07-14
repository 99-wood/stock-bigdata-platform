<template>
  <div id="app-root">
    <router-view />
  </div>
</template>

<script setup>
</script>

<style>
/* ===================================================================
   Design System — inspired by OpenStock's dark-only layered palette
   Spatial hierarchy via luminosity: #050505 → #141414 → #212328 → #30333A
   =================================================================== */

:root {
  /* ── Background hierarchy (dark → light) ── */
  --bg-root:      #050505;
  --bg-surface:   #0D0D0D;
  --bg-elevated:  #141414;
  --bg-header:    #1A1A1E;
  --bg-hover:     rgba(255,255,255,0.04);
  --bg-active:    rgba(15,237,190,0.08);
  --bg-input:     #0F0F0F;

  /* ── Borders ── */
  --border-default:  #30333A;
  --border-subtle:   rgba(255,255,255,0.06);
  --border-card:     rgba(255,255,255,0.08);
  --border-strong:   rgba(255,255,255,0.12);
  --border-accent:   rgba(15,237,190,0.25);

  /* ── Text ── */
  --text-primary:    #E8ECF1;
  --text-secondary:  #9095A1;
  --text-muted:      #5A6070;
  --text-white:      #FFFFFF;

  /* ── Accent: Teal ── */
  --accent:          #0FEDBE;
  --accent-hover:    #0CD9AD;
  --accent-bg:       rgba(15,237,190,0.10);
  --accent-bg-hover: rgba(15,237,190,0.18);
  --accent-text:     #0A0A0A;

  /* ── Semantic: Stock directions (A-share: red=up, green=down) ── */
  --stock-up:        #FF495B;
  --stock-up-bg:     rgba(255,73,91,0.12);
  --stock-up-border: rgba(255,73,91,0.25);
  --stock-down:      #3FB950;
  --stock-down-bg:   rgba(63,185,80,0.12);
  --stock-down-border: rgba(63,185,80,0.25);
  --stock-flat:      #9095A1;
  --stock-warn:      #E8BA40;
  --stock-warn-bg:   rgba(232,186,64,0.12);
  --stock-warn-border: rgba(232,186,64,0.25);

  /* ── Radius system ── */
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --radius-xl: 16px;
  --radius-full: 9999px;

  /* ── Shadows ── */
  --shadow-sm:  0 1px 2px rgba(0,0,0,0.3);
  --shadow-md:  0 4px 12px rgba(0,0,0,0.4);
  --shadow-glass: 0 4px 24px rgba(0,0,0,0.5);
  --shadow-glow: 0 0 20px rgba(15,237,190,0.15);

  /* ── Typography ── */
  --font-sans: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif;
  --font-mono: 'JetBrains Mono', 'Fira Code', 'Cascadia Code', 'Consolas', monospace;

  /* ── Element Plus overrides ── */
  --el-color-primary: var(--accent);
  --el-color-primary-light-5: rgba(15,237,190,0.3);
  --el-color-primary-light-9: rgba(15,237,190,0.05);
  --el-bg-color: var(--bg-elevated);
  --el-bg-color-overlay: var(--bg-elevated);
  --el-text-color-primary: var(--text-primary);
  --el-text-color-regular: var(--text-secondary);
  --el-text-color-placeholder: var(--text-muted);
  --el-border-color: var(--border-default);
  --el-fill-color: var(--bg-surface);
  --el-fill-color-light: var(--bg-elevated);
  --el-fill-color-blank: var(--bg-input);
  --el-color-success: var(--stock-down);
  --el-color-warning: var(--stock-warn);
  --el-color-danger: var(--stock-up);
}

/* ── Reset ── */
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}

html {
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

body {
  font-family: var(--font-sans);
  background: var(--bg-root);
  color: var(--text-primary);
  line-height: 1.5;
  min-height: 100vh;
  overflow-x: hidden;
}

#app-root {
  min-height: 100vh;
}

/* ── Scrollbar (hidden until hover, like OpenStock) ── */
::-webkit-scrollbar{width:5px;height:5px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:transparent;border-radius:3px;transition:background .3s}
*:hover::-webkit-scrollbar-thumb{background:var(--border-default)}
::-webkit-scrollbar-thumb:hover{background:var(--text-muted)}
*{scrollbar-width:thin;scrollbar-color:transparent transparent}
*:hover{scrollbar-color:var(--border-default) transparent}

/* ── Global stock color classes ── */
.code-up{color:var(--stock-up)!important}
.code-down{color:var(--stock-down)!important}
.code-suspended{color:var(--stock-warn)!important}
.code-delisted{color:var(--text-muted)!important}
.code-flat{color:var(--text-secondary)!important}
.code-neutral{color:var(--text-primary)}

/* ── Gradient text mixin (applied via class) ── */
.gradient-text {
  background: linear-gradient(135deg, var(--text-white) 0%, var(--text-secondary) 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

/* ── KPI label micro-pattern ── */
.kpi-label {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  color: var(--text-muted);
}

/* ── Element Plus dark refinements ── */
.el-button--primary {
  --el-button-bg-color: var(--accent);
  --el-button-border-color: var(--accent);
  --el-button-text-color: var(--accent-text);
  font-weight: 600;
}
.el-button--primary:hover {
  --el-button-bg-color: var(--accent-hover);
  --el-button-border-color: var(--accent-hover);
}

.el-input__wrapper {
  background: var(--bg-input) !important;
  border-color: var(--border-default) !important;
  box-shadow: none !important;
  border-radius: var(--radius-md) !important;
  transition: border-color .15s !important;
}
.el-input__wrapper:hover { border-color: var(--border-strong) !important }
.el-input__wrapper.is-focus {
  border-color: var(--accent) !important;
  box-shadow: 0 0 0 1px var(--accent-bg) !important;
}
.el-input__inner {
  color: var(--text-primary) !important;
  font-family: var(--font-mono) !important;
  font-size: 13px !important;
}
.el-input__inner::placeholder { color: var(--text-muted) !important }

.el-select-dropdown {
  background: var(--bg-elevated) !important;
  border: 1px solid var(--border-default) !important;
  border-radius: var(--radius-md) !important;
}
.el-select-dropdown__item { color: var(--text-primary) !important }
.el-select-dropdown__item:hover { background: var(--accent-bg) !important }

.el-tag--dark.el-tag--success { background: var(--stock-down-bg); color: var(--stock-down) }
.el-tag--dark.el-tag--danger  { background: var(--stock-up-bg); color: var(--stock-up) }

.el-skeleton__item { background: var(--bg-elevated) !important }

.el-result__title { color: var(--text-primary) !important }
.el-result__subtitle { color: var(--text-secondary) !important }
</style>
