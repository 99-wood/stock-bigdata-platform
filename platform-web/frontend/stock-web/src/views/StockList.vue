<template>
  <div class="terminal">
    <!-- ═══ LEFT: Stock List ═══ -->
    <aside class="side-list">
      <div class="sl-top">
        <div class="sl-top-row">
          <router-link to="/" class="sl-back"><el-icon><ArrowLeft /></el-icon></router-link>
          <h2 class="sl-title">STOCK SCANNER</h2>
          <span class="sl-chip">{{ filtered.length }} / {{ all.length }}</span>
        </div>
        <el-input v-model="search" placeholder="搜索代码..." clearable size="small">
          <template #prefix><span class="sl-prompt">&gt;</span></template>
        </el-input>
      </div>

      <div v-if="loading" class="sl-loading"><el-skeleton :rows="10" animated /></div>

      <div v-else-if="!all.length" class="sl-empty">
        <div class="se-icon">!</div>
        <span>暂无股票行情</span>
      </div>

      <div v-else class="sl-body">
        <div
          v-for="(row, i) in paged" :key="row.code"
          class="sl-row group" :class="{ sel: selectedCode === row.code }"
          @click="select(row)"
        >
          <span class="slr-idx">{{ offset + i }}</span>
          <span class="slr-name">{{ row.name || row.code }}</span>
          <span class="slr-bid">{{ fmtBA(row,'bid') }}</span>
          <span class="slr-ask">{{ fmtBA(row,'ask') }}</span>
          <span class="slr-spr">{{ spread(row) }}</span>
        </div>
      </div>

      <div v-if="filtered.length > pageSize" class="sl-foot">
        <button class="pg-btn" :disabled="page<=1" @click="page--">&laquo;</button>
        <span class="pg-info">{{ page }} / {{ totalPages }}</span>
        <button class="pg-btn" :disabled="page>=totalPages" @click="page++">&raquo;</button>
      </div>
    </aside>

    <!-- ═══ RIGHT: Detail ═══ -->
    <main class="side-detail">
      <template v-if="!selected">
        <div class="ph">
          <svg class="ph-icon" width="56" height="56" viewBox="0 0 56 56" fill="none">
            <rect x="4" y="8" width="48" height="40" rx="4" stroke="currentColor" stroke-width="1.5"/>
            <line x1="4" y1="20" x2="52" y2="20" stroke="currentColor" stroke-width="1"/>
            <line x1="20" y1="20" x2="20" y2="48" stroke="currentColor" stroke-width="1"/>
            <line x1="36" y1="20" x2="36" y2="48" stroke="currentColor" stroke-width="1"/>
            <rect x="22" y="26" width="12" height="5" rx="1" fill="currentColor" opacity=".25"/>
            <rect x="22" y="35" width="12" height="5" rx="1" fill="currentColor" opacity=".25"/>
            <rect x="42" y="26" width="8" height="5" rx="1" fill="currentColor" opacity=".1"/>
            <rect x="42" y="35" width="8" height="5" rx="1" fill="currentColor" opacity=".1"/>
          </svg>
          <p class="ph-title">证券交互终端</p>
          <p class="ph-desc">从左侧列表选择股票<br/>查看实时五档行情</p>
          <div class="ph-stats">
            <div class="phs"><span class="phs-val">{{ all.length }}</span><span class="phs-lbl">标的</span></div>
            <div class="phs"><span class="phs-val up">{{ sm.up }}</span><span class="phs-lbl">上涨</span></div>
            <div class="phs"><span class="phs-val down">{{ sm.down }}</span><span class="phs-lbl">下跌</span></div>
          </div>
        </div>
      </template>
      <StockDetailPanel v-else :stock="selected" />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'
import { stockApi } from '@/api/request'
import StockDetailPanel from '@/components/StockDetailPanel.vue'

const all = ref([]); const loading = ref(true)
const search = ref(''); const page = ref(1); const pageSize = 25
const selected = ref(null)
const selectedCode = computed(() => selected.value?.code ?? null)

const filtered = computed(() => {
  const kw = search.value.trim().toLowerCase()
  return kw ? all.value.filter(s => s.code?.toLowerCase().includes(kw)) : all.value
})
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const paged = computed(() => filtered.value.slice((page.value-1)*pageSize, page.value*pageSize))
const offset = computed(() => (page.value-1)*pageSize+1)

function fmtBA(r, side) {
  const b=Number(r.bid), a=Number(r.ask)
  if (side==='bid') { if(b>0)return b.toFixed(2); if(a>0)return'跌停'; return'停牌' }
  if (a>0)return a.toFixed(2); if(b>0)return'涨停'; return'停牌'
}
function spread(r) {
  if (r.bid==null||r.ask==null) return'--'
  const b=Number(r.bid),a=Number(r.ask)
  if(b>0&&a===0)return'涨停'; if(a>0&&b===0)return'跌停'; if(b===0&&a===0)return'停牌'
  return (a-b).toFixed(3)
}
function select(row) { selected.value = row }

const sm = computed(() => {
  let up=0,down=0
  for (const s of all.value) { if (s.status==='00')up++; else if (s.status==='-2')down++ }
  return {up,down}
})

onMounted(async () => {
  try {
    const data = (await stockApi.getStockList()) || []
    all.value = data
    if (data.length) selected.value = data[0]
  } catch { /* empty */ }
  finally { loading.value = false }
})
</script>

<style scoped>
.terminal { display: flex; height: 100vh; background: var(--bg-root); overflow: hidden }

/* ═══ Left Panel ═══ */
.side-list {
  width: 35%; min-width: 330px; max-width: 440px;
  display: flex; flex-direction: column;
  border-right: 1px solid var(--border-subtle); background: var(--bg-surface);
}
.sl-top { padding: 14px 16px 12px; border-bottom: 1px solid var(--border-subtle); flex-shrink: 0 }
.sl-top-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px }

.sl-back {
  display: flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; background: var(--bg-elevated);
  border: 1px solid var(--border-subtle); border-radius: var(--radius-sm);
  color: var(--text-secondary); text-decoration: none; transition: all .15s;
}
.sl-back:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-bg) }

.sl-title { font-size: 11px; font-weight: 700; letter-spacing: .1em; color: var(--text-muted); flex:1; margin:0 }
.sl-chip {
  font-size: 10px; font-weight: 600; color: var(--text-muted);
  background: var(--bg-elevated); padding: 2px 8px; border-radius: var(--radius-full);
  font-family: var(--font-mono);
}
.sl-prompt { color: var(--accent); font-family: var(--font-mono); font-size: 13px; font-weight: 700 }

.sl-body { flex: 1; overflow-y: auto }

.sl-row {
  display: flex; align-items: center; height: 35px; padding: 0 16px; gap: 10px;
  border-bottom: 1px solid rgba(255,255,255,.02); cursor: pointer;
  transition: background .08s; font-family: var(--font-mono); font-size: 12px;
}
.sl-row:hover { background: var(--bg-hover) }
.sl-row.sel { background: var(--accent-bg); border-left: 2px solid var(--accent); padding-left: 14px }

.slr-idx { width: 24px; color: var(--text-muted); font-size: 10px; flex-shrink: 0; text-align: right }
.slr-name {
  flex: 1; font-weight: 500; font-size: 12px; color: var(--text-primary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--font-sans);
}
.slr-bid, .slr-ask { width: 62px; text-align: right; font-weight: 500; font-size: 11px }
.slr-bid { color: var(--stock-down) }
.slr-ask { color: var(--stock-up) }
.slr-spr { width: 48px; text-align: right; color: var(--text-muted); font-size: 10px }

.sl-loading { padding: 24px 16px }
.sl-empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; color:var(--text-muted); font-size:13px }
.se-icon { width:32px;height:32px;border:1px solid var(--stock-warn);color:var(--stock-warn);display:flex;align-items:center;justify-content:center;border-radius:50%;font-weight:700;font-size:15px }

.sl-foot { padding: 10px 16px; border-top: 1px solid var(--border-subtle); flex-shrink: 0; display: flex; align-items: center; justify-content: center; gap: 12px }

.pg-btn {
  width: 28px; height: 28px; background: var(--bg-elevated);
  border: 1px solid var(--border-subtle); border-radius: var(--radius-sm);
  color: var(--text-secondary); cursor: pointer; font-size: 13px;
  font-family: var(--font-mono); display: flex; align-items: center; justify-content: center;
  transition: all .15s;
}
.pg-btn:hover:not(:disabled) { border-color: var(--accent); color: var(--accent) }
.pg-btn:disabled { opacity: .2; cursor: default }

.pg-info { font-size: 11px; color: var(--text-muted); font-family: var(--font-mono); min-width: 56px; text-align: center }

/* ═══ Right Panel ═══ */
.side-detail { flex: 1; overflow-y: auto; padding: 24px 32px; background: var(--bg-root) }

/* Placeholder */
.ph {
  flex: 1; display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 12px; min-height: 100%;
}
.ph-icon { color: var(--border-default); margin-bottom: 4px }
.ph-title { font-size: 18px; font-weight: 600; color: var(--text-muted); letter-spacing: .02em }
.ph-desc { font-size: 13px; color: var(--text-muted); text-align: center; line-height: 1.6; opacity: .65 }
.ph-stats { display: flex; gap: 36px; margin-top: 20px }
.phs { text-align: center }
.phs-val { display: block; font-size: 26px; font-weight: 700; font-family: var(--font-mono); color: var(--text-secondary) }
.phs-val.up { color: var(--stock-up) }
.phs-val.down { color: var(--stock-down) }
.phs-lbl { font-size: 10px; font-weight: 600; text-transform: uppercase; letter-spacing: .1em; color: var(--text-muted) }

@media (max-width: 860px) {
  .terminal { flex-direction: column }
  .side-list { width:100%; max-width:100%; height:45vh; border-right:none; border-bottom:1px solid var(--border-subtle) }
  .side-detail { padding: 16px }
}
</style>
