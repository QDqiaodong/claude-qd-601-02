<template>
  <div v-loading="loading" element-loading-background="rgba(255,255,255,0.45)">
    <div class="eq-panel">
      <div class="eq-panel__head">
        <h2>马房栏位分布</h2>
        <div class="board__actions">
          <span v-if="loadedAt" class="eq-muted">
            最近刷新 {{ loadedAtLabel }}
          </span>
          <el-button :loading="loading" @click="refresh()">刷新</el-button>
          <el-button type="primary" @click="stallDialog = true">新增栏位</el-button>
        </div>
      </div>

      <!-- 请求失败：保留上一次可信结果，明确提示；下一次成功加载后提示消失，不留任何旧统计缓存 -->
      <el-alert
        v-if="loadError"
        class="board__alert"
        type="error"
        show-icon
        :closable="false"
        :title="loadError"
      >
        <div class="board__alert__foot">
          <span>当前展示的是最近一次成功加载的数据，请稍后重试。</span>
          <el-button size="small" type="danger" plain :loading="loading" @click="refresh()">
            重新加载
          </el-button>
        </div>
      </el-alert>

      <div v-if="horseWarning" class="board__warn eq-muted">马匹列表暂未更新：{{ horseWarning }}，入栏可选马匹仍为最近一次成功加载的结果。</div>

      <div class="summary">
        <div class="summary__item">
          <span class="summary__num">{{ summary.total }}</span>
          <span class="summary__label">栏位总数</span>
        </div>
        <div class="summary__item summary__item--idle">
          <span class="summary__num">{{ summary.idle }}</span>
          <span class="summary__label">空闲</span>
        </div>
        <div class="summary__item summary__item--occupied">
          <span class="summary__num">{{ summary.occupied }}</span>
          <span class="summary__label">占用</span>
        </div>
        <div class="summary__item summary__item--maintenance">
          <span class="summary__num">{{ summary.maintenance }}</span>
          <span class="summary__label">维护中</span>
        </div>
        <div class="summary__tip">
          规则：栏位编号唯一 · 一个栏位只放一匹马 · 一匹马只占一个栏位 · 维护中禁止入栏<br />
          顶部汇总、马房分组与卡片信息由同一次栏位明细计算，同轮切换。
        </div>
      </div>
    </div>

    <el-empty
      v-if="!loading && !loadError && groups.length === 0"
      class="board__empty"
      description="暂无栏位，点击右上角「新增栏位」创建"
    />

    <div v-for="group in groups" :key="group.barn" class="eq-panel barn">
      <div class="eq-panel__head">
        <h2>{{ group.barn }}</h2>
        <span>
          {{ group.total }} 个栏位 · 空闲 {{ group.idle }} · 已用 {{ group.occupied }} · 维护 {{ group.maintenance }}
        </span>
      </div>

      <div class="stalls">
        <div
          v-for="stall in group.stalls"
          :key="stall.id"
          class="stall"
          :class="'stall--' + stall.status.toLowerCase()"
        >
          <div class="stall__head">
            <span class="stall__no">{{ stall.stallNo }}</span>
            <el-tag size="small" :type="tagOf(stall.status)" effect="dark">{{ stall.statusName }}</el-tag>
          </div>
          <div class="stall__area">{{ stall.areaSqm === null ? '面积未登记' : stall.areaSqm + ' ㎡' }}</div>
          <div class="stall__horse">
            <template v-if="stall.horseId">
              <b>{{ stall.horseName }}</b>
              <span>{{ stall.horseNo }}</span>
            </template>
            <span v-else class="eq-muted">空栏</span>
          </div>
          <div class="stall__ops">
            <el-button v-if="stall.status === 'IDLE'" size="small" type="primary" @click="openOccupy(stall)">
              入栏
            </el-button>
            <el-button v-if="stall.status === 'IDLE'" size="small" @click="toMaintenance(stall)">
              转维护
            </el-button>
            <el-button v-if="stall.status === 'OCCUPIED'" size="small" type="primary" @click="release(stall)">
              出栏
            </el-button>
            <el-button v-if="stall.status === 'MAINTENANCE'" size="small" type="success" @click="restore(stall)">
              维护完成
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="stallDialog" title="新增栏位" width="440px">
      <el-form label-width="88px">
        <el-form-item label="栏位编号">
          <el-input v-model="stallForm.stallNo" placeholder="如 STM-010" />
        </el-form-item>
        <el-form-item label="所属马房">
          <el-input v-model="stallForm.barnName" placeholder="如 A 区马房" />
        </el-form-item>
        <el-form-item label="面积 ㎡">
          <el-input-number v-model="stallForm.areaSqm" :min="0" :max="500" :precision="1" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stallDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitStall">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="occupyDialog" title="安排马匹入栏" width="440px">
      <p class="eq-muted">
        目标栏位：<b>{{ occupyForm.stallNo }}</b>（{{ occupyForm.barnName }}）
      </p>
      <el-select v-model="occupyForm.horseId" placeholder="选择要入栏的马匹" style="width: 100%">
        <el-option
          v-for="horse in horses"
          :key="horse.id"
          :label="horse.horseNo + ' ' + horse.name + '（' + horse.statusName + '）'"
          :value="horse.id"
        />
      </el-select>
      <p class="eq-muted" style="margin-top: 10px">
        已在别的栏位的马匹、维护中的栏位都会在提交时被后端拒绝并返回中文原因。
      </p>
      <template #footer>
        <el-button @click="occupyDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitOccupy">确认入栏</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const KNOWN_STATUS = ['IDLE', 'OCCUPIED', 'MAINTENANCE']
const STATUS_NAME = { IDLE: '空闲', OCCUPIED: '占用', MAINTENANCE: '维护中' }
const UNGROUPED_BARN = '未分组马房'

// 页面状态：stalls 是看板唯一的明细来源，顶部汇总 / 马房分组 / 卡片全部由它派生
const stalls = ref([])
// 入栏下拉用的马匹档案，来自独立接口；它失败不能影响栏位看板本身
const horses = ref([])
const loading = ref(false)
const loadError = ref('')
const horseWarning = ref('')
const loadedAt = ref(null)

const saving = ref(false)
const stallDialog = ref(false)
const occupyDialog = ref(false)

const stallForm = reactive({ stallNo: '', barnName: '', areaSqm: null })
const occupyForm = reactive({ id: null, stallNo: '', barnName: '', horseId: null })

// 过期响应防护：手动刷新与操作后自动刷新可能并发，只有最后一轮请求能写状态
let loadSeq = 0

function isObject(value) {
  return value !== null && typeof value === 'object'
}

function textOf(value, fallback) {
  if (typeof value === 'number') return String(value)
  if (typeof value === 'string') {
    const trimmed = value.trim()
    return trimmed === '' ? fallback : trimmed
  }
  return fallback
}

/**
 * 规范化栏位明细：
 *  - 只接受带 id 的对象，按 id 去重，返回顺序变化也不会出现重复卡片 / 重复计数
 *  - 状态只认 IDLE / OCCUPIED / MAINTENANCE，异常值按空闲兜底，保证三个计数恒等于总数
 *  - 空马房归入「未分组马房」、空面积保留 null 走「面积未登记」
 *  - 卡片上的马匹信息只认这一批明细里的 horseId / horseName / horseNo
 */
function normalizeStalls(list) {
  if (!Array.isArray(list)) return []
  const map = new Map()
  for (const item of list) {
    if (!isObject(item)) continue
    const id = Number(item.id)
    if (!Number.isFinite(id) || map.has(id)) continue
    const rawStatus = textOf(item.status, 'IDLE')
    const status = KNOWN_STATUS.includes(rawStatus) ? rawStatus : 'IDLE'
    const rawHorseId = item.horseId === null || item.horseId === undefined || item.horseId === ''
      ? null
      : Number(item.horseId)
    const hasArea = item.areaSqm !== null && item.areaSqm !== undefined && item.areaSqm !== ''
      && Number.isFinite(Number(item.areaSqm))
    map.set(id, {
      id,
      stallNo: textOf(item.stallNo, `#${id}`),
      barnName: textOf(item.barnName, UNGROUPED_BARN),
      areaSqm: hasArea ? Number(item.areaSqm) : null,
      status,
      // 后端已知状态会给中文名；异常状态统一显示兜底中文名
      statusName: STATUS_NAME[status],
      horseId: Number.isFinite(rawHorseId) ? rawHorseId : null,
      horseNo: textOf(item.horseNo, '编号缺失'),
      horseName: textOf(item.horseName, '马匹档案缺失')
    })
  }
  // 顺序以 id 为准稳定排序，后端返回顺序变化不影响分组与卡片
  return [...map.values()].sort((a, b) => a.id - b.id)
}

function normalizeHorses(list) {
  if (!Array.isArray(list)) return []
  const map = new Map()
  for (const item of list) {
    if (!isObject(item)) continue
    const id = Number(item.id)
    if (!Number.isFinite(id) || map.has(id)) continue
    map.set(id, {
      id,
      horseNo: textOf(item.horseNo, `#${id}`),
      name: textOf(item.name, '未命名马匹'),
      statusName: textOf(item.statusName, '')
    })
  }
  return [...map.values()].sort((a, b) => a.id - b.id)
}

function emptySummary() {
  return { total: 0, idle: 0, occupied: 0, maintenance: 0 }
}

/**
 * 同一份快照同时产出「顶部汇总」和「马房分组」，
 * 任何数字都可以从 groups[].stalls 追溯到具体栏位卡片。
 */
const groups = computed(() => {
  const map = new Map()
  for (const stall of stalls.value) {
    let group = map.get(stall.barnName)
    if (!group) {
      group = { barn: stall.barnName, stalls: [], ...emptySummary() }
      map.set(stall.barnName, group)
    }
    group.stalls.push(stall)
    group.total += 1
    if (stall.status === 'OCCUPIED') {
      group.occupied += 1
    } else if (stall.status === 'MAINTENANCE') {
      group.maintenance += 1
    } else {
      group.idle += 1
    }
  }
  // 马房顺序按本批数据首次出现顺序（normalizeStalls 已按 id 稳定排序）
  return [...map.values()]
})

const summary = computed(() => {
  const total = emptySummary()
  for (const group of groups.value) {
    total.total += group.total
    total.idle += group.idle
    total.occupied += group.occupied
    total.maintenance += group.maintenance
  }
  return total
})

const loadedAtLabel = computed(() => {
  if (!loadedAt.value) return ''
  const pad = (n) => String(n).padStart(2, '0')
  const t = loadedAt.value
  return `${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())}`
})

function reasonOf(error, fallback) {
  return error && error.message ? error.message : fallback
}

/**
 * 拉取看板数据。
 * 口径：栏位明细是看板的唯一来源；马匹下拉独立处理。
 *  - 栏位接口失败：保留上一次成功结果并报错，绝不用空数组覆盖（首次进入则为空态 + 错误条）
 *  - 马匹接口失败：不影响栏位统计与卡片，只在顶部弱化提示，下拉保留旧列表
 *  - 只有两个结果都到手并经过规范化后，stalls / horses 才在同一轮同步赋值，
 *    顶部汇总、分组、卡片在同一次渲染中切换，不会短暂出现互相矛盾的数字
 *  - 用 loadSeq 丢弃过期响应，快速连续刷新也不会被旧响应回写
 */
async function load({ manual = false } = {}) {
  const seq = ++loadSeq
  loading.value = true
  try {
    const [stallResult, horseResult] = await Promise.allSettled([api.stalls(), api.horses()])
    if (seq !== loadSeq) return

    if (stallResult.status === 'rejected') {
      loadError.value = '栏位看板加载失败：' + reasonOf(stallResult.reason, '网络异常，请稍后重试')
      if (manual) ElMessage.error(loadError.value)
      if (horseResult.status === 'fulfilled') {
        horses.value = normalizeHorses(horseResult.value)
        horseWarning.value = ''
      } else {
        horseWarning.value = reasonOf(horseResult.reason, '网络异常，请稍后重试')
      }
      return false
    }

    // 同轮数据：先规范化，再一次性同步替换，保证汇总 / 分组 / 卡片同源同时切换
    const nextStalls = normalizeStalls(stallResult.value)
    stalls.value = nextStalls
    loadError.value = ''
    loadedAt.value = new Date()

    if (horseResult.status === 'fulfilled') {
      horses.value = normalizeHorses(horseResult.value)
      horseWarning.value = ''
    } else {
      horseWarning.value = reasonOf(horseResult.reason, '网络异常，请稍后重试')
      ElMessage.error('马匹列表加载失败：' + horseWarning.value + '，栏位统计不受影响')
    }
    return true
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

/** 手动刷新 / 错误条重试 */
async function refresh() {
  const ok = await load({ manual: true })
  if (ok) ElMessage.success('看板已刷新')
  return ok
}

async function withSaving(action) {
  saving.value = true
  try {
    await action()
  } catch (error) {
    ElMessage.error(reasonOf(error, '操作失败'))
  } finally {
    saving.value = false
  }
}

async function submitStall() {
  await withSaving(async () => {
    await api.createStall({
      stallNo: stallForm.stallNo,
      barnName: stallForm.barnName,
      areaSqm: stallForm.areaSqm
    })
    ElMessage.success('栏位已创建')
    stallDialog.value = false
    Object.assign(stallForm, { stallNo: '', barnName: '', areaSqm: null })
    // 操作成功后自动刷新，重新走同一条一致性加载链路
    await load()
  })
}

function openOccupy(stall) {
  occupyForm.id = stall.id
  occupyForm.stallNo = stall.stallNo
  occupyForm.barnName = stall.barnName
  occupyForm.horseId = null
  occupyDialog.value = true
}

async function submitOccupy() {
  await withSaving(async () => {
    await api.occupyStall(occupyForm.id, occupyForm.horseId)
    ElMessage.success('入栏成功')
    occupyDialog.value = false
    await load()
  })
}

async function release(stall) {
  try {
    await api.releaseStall(stall.id)
    ElMessage.success(`栏位 ${stall.stallNo} 已出栏`)
    await load()
  } catch (error) {
    ElMessage.error(reasonOf(error, '操作失败'))
  }
}

async function toMaintenance(stall) {
  try {
    await api.maintenanceStall(stall.id)
    ElMessage.success(`栏位 ${stall.stallNo} 已转入维护`)
    await load()
  } catch (error) {
    ElMessage.error(reasonOf(error, '操作失败'))
  }
}

async function restore(stall) {
  try {
    await api.restoreStall(stall.id)
    ElMessage.success(`栏位 ${stall.stallNo} 维护完成`)
    await load()
  } catch (error) {
    ElMessage.error(reasonOf(error, '操作失败'))
  }
}

function tagOf(status) {
  if (status === 'IDLE') return 'success'
  if (status === 'OCCUPIED') return 'primary'
  return 'warning'
}

onMounted(() => load())
</script>

<style scoped>
.board__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.board__alert {
  margin-bottom: 12px;
}

.board__alert__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.board__warn {
  margin: 0 0 12px;
  padding: 8px 12px;
  background: #fdf6ec;
  border: 1px solid #f5e8d3;
  border-radius: 8px;
  color: #a07a45;
}

.board__empty {
  margin-top: 28px;
  background: #fff;
  border: 1px dashed #e3d3cb;
  border-radius: 12px;
}

.summary {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.summary__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 84px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #f7f2ef;
  border: 1px solid #ece1db;
}

.summary__item--idle {
  background: #f0f7f1;
  border-color: #d6e8d9;
}

.summary__item--occupied {
  background: #f3e9e4;
  border-color: #e0cec5;
}

.summary__item--maintenance {
  background: #fdf4e7;
  border-color: #f0e0c6;
}

.summary__num {
  font-size: 21px;
  font-weight: 700;
  color: #4a3129;
}

.summary__label {
  font-size: 11px;
  color: #8c7c76;
}

.summary__tip {
  flex: 1;
  min-width: 220px;
  font-size: 12px;
  color: #9a8b85;
  line-height: 1.7;
}

.barn {
  margin-top: 16px;
}

.stalls {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 12px;
}

.stall {
  border-radius: 10px;
  border: 1px solid #ece3df;
  padding: 12px;
  background: #fffdfc;
}

.stall--occupied {
  background: #fdf7f4;
  border-color: #ddc9bf;
}

.stall--maintenance {
  background: repeating-linear-gradient(45deg, #fdf8f1, #fdf8f1 8px, #f8f0e3 8px, #f8f0e3 16px);
  border-color: #e8d8be;
}

.stall__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.stall__no {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #4a3129;
}

.stall__area {
  font-size: 11px;
  color: #a08d86;
  margin-top: 3px;
}

.stall__horse {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin: 9px 0;
  min-height: 22px;
  font-size: 13px;
  color: #4a3129;
}

.stall__horse span {
  font-size: 11px;
  color: #a08d86;
}

.stall__ops {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  border-top: 1px dashed #efe6e1;
  padding-top: 8px;
}
</style>
