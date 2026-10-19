<script setup>
import EventHistory from './components/EventHistory.vue'
import * as echarts from 'echarts'
import { ref, computed, onMounted, onUnmounted } from 'vue'

const device = ref(null)
// ECharts 容器
const chartEl = ref(null)

let chart = null

// 只保留最近 60 条设备上报数据
const MAX_POINTS = 60
const history = []

// 防止 REST 和 WebSocket 重复加入同一条数据
let lastChartTimestamp = null

// 最近一次冲击事件的时间
const lastImpactAt = ref(0)

const showImpactAlert = computed(() => {
  return lastImpactAt.value > 0 &&
    now.value - lastImpactAt.value < 10000
})

function initChart() {
  chart = echarts.init(chartEl.value)

  chart.setOption({
    animation: false,

    tooltip: {
      trigger: 'axis'
    },

    legend: {
      data: ['X 轴', 'Y 轴', 'Z 轴']
    },

    grid: {
      left: 65,
      right: 25,
      top: 65,
      bottom: 45
    },

    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: [],
      axisLabel: {
        hideOverlap: true
      }
    },

    yAxis: {
      type: 'value',
      scale: true,
      name: '加速度原始值'
    },

    series: [
      {
        name: 'X 轴',
        type: 'line',
        showSymbol: false,
        data: []
      },
      {
        name: 'Y 轴',
        type: 'line',
        showSymbol: false,
        data: []
      },
      {
        name: 'Z 轴',
        type: 'line',
        showSymbol: false,
        data: []
      }
    ]
  })
}

function refreshChart() {
  if (!chart) return

  chart.setOption({
    xAxis: {
      data: history.map(item => item.time)
    },

    series: [
      { showSymbol: history.length === 1, data: history.map(item => item.x) },
      { showSymbol: history.length === 1, data: history.map(item => item.y) },
      { showSymbol: history.length === 1, data: history.map(item => item.z) }
    ]
  })
}

function updateDevice(status) {
  if (!status || typeof status !== 'object' || !status.receivedAt) return

  const timestamp = new Date(status.receivedAt).getTime()
  // REST 初始请求和重连响应可能晚于实时消息，忽略重复或过时的数据。
  if (!Number.isFinite(timestamp) ||
      (lastChartTimestamp !== null && timestamp <= lastChartTimestamp)) {
    return
  }

  lastChartTimestamp = timestamp
  device.value = status

  if (status.armed && status.state === 'IMPACT') {
    now.value = Date.now()
    lastImpactAt.value = now.value
  }

  // 撤防和无效加速度数据不作为采样点。
  if (!status.armed || ![status.x, status.y, status.z].every(Number.isFinite)) return

  history.push({
    time: new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false }),
    x: status.x,
    y: status.y,
    z: status.z
  })

  if (history.length > MAX_POINTS) history.shift()
  refreshChart()
}

function resizeChart() {
  chart?.resize()
}

const wsConnected = ref(false)
const loading = ref(true)
const error = ref('')
const now = ref(Date.now())

let ws = null
let reconnectTimer = null
let clockTimer = null
let stopped = false

// 页面首次加载或 WebSocket 重连时获取最新状态
async function loadLatest() {
  try {
    const response = await fetch('/api/device/status', {
      cache: 'no-store'
    })

    if (response.status === 204) {
      error.value = ''
      return
    }

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    updateDevice(await response.json())
    error.value = ''
  } catch (e) {
    error.value = `REST 请求失败：${e.message}`
  } finally {
    loading.value = false
  }
}

// 连接 WebSocket
function connectWebSocket() {
  const protocol =
    window.location.protocol === 'https:' ? 'wss:' : 'ws:'

  ws = new WebSocket(
    `${protocol}//${window.location.host}/ws/device`
  )

  ws.onopen = () => {
    wsConnected.value = true
    error.value = ''
    loadLatest()
  }

  ws.onmessage = (event) => {
    try {
      updateDevice(JSON.parse(event.data))
      error.value = ''
    } catch (e) {
      error.value = `实时消息解析失败：${e.message}`
    }
  }

  ws.onerror = () => {
    error.value = 'WebSocket 连接异常'
    ws.close()
  }

  ws.onclose = () => {
    wsConnected.value = false

    if (!stopped) {
      reconnectTimer = setTimeout(connectWebSocket, 3000)
    }
  }
}

const stateText = computed(() => {
  return device.value?.state ?? '等待数据'
})

const stateClass = computed(() => {
  return (device.value?.state ?? 'UNKNOWN').toLowerCase()
})

const lastReceived = computed(() => {
  if (!device.value?.receivedAt) return '--'

  return new Date(device.value.receivedAt).toLocaleString()
})

// WebSocket 连通不等于 BearPi 在线。
// 已布防时，依据最近一次上报时间提示数据新鲜度。
const telemetryStatus = computed(() => {
  if (!device.value) return '等待设备上报'

  if (!device.value.armed) {
    return '已撤防，暂无持续心跳'
  }

  const received =
    new Date(device.value.receivedAt).getTime()

  if (!Number.isFinite(received)) {
    return '上报时间未知'
  }

  return now.value - received <= 10000
    ? '最近 10 秒内有上报'
    : '超过 10 秒未上报'
})

onMounted(() => {
  initChart()
  window.addEventListener('resize', resizeChart)
  loadLatest()
  connectWebSocket()

  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  stopped = true
  clearTimeout(reconnectTimer)
  clearInterval(clockTimer)
  ws?.close()
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <main class="dashboard">
    <header class="header">
      <div>
        <p class="eyebrow">BEARPI · OPENHARMONY</p>
        <h1>智能设备姿态监测系统</h1>
        <p class="subtitle">
          MPU6050 · MQTT · Spring Boot · Vue3
        </p>
      </div>

      <div
        class="connection"
        :class="{ connected: wsConnected }"
      >
        <span class="dot"></span>
        {{ wsConnected ? 'WebSocket 已连接' : '正在重连' }}
      </div>
    </header>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-if="showImpactAlert" class="impact-alert" role="alert">
      <strong>⚠ 检测到冲击事件</strong>
      <p>设备上报 IMPACT，请检查设备状态。</p>
    </div>

    <section class="cards">
      <article class="card">
        <span class="label">设备状态</span>
        <h2 :class="['device-state', stateClass]">
          {{ stateText }}
        </h2>
        <p>{{ telemetryStatus }}</p>
      </article>

      <article class="card">
        <span class="label">布防状态</span>
        <h2>
          {{ device ? (device.armed ? 'ARMED' : 'DISARMED') : '--' }}
        </h2>
        <p>通过 BearPi F1 按键切换</p>
      </article>

      <article class="card">
        <span class="label">设备温度</span>
        <h2>
          {{ device ? `${device.temperature} °C` : '--' }}
        </h2>
        <p>MPU6050 温度数据</p>
      </article>
    </section>

    <section class="panel">
      <div class="section-heading">
        <h2>三轴加速度</h2>
        <span>实时采样数据</span>
      </div>

      <div class="axes">
        <div
          v-for="axis in ['x', 'y', 'z']"
          :key="axis"
          class="axis"
        >
          <span>{{ axis.toUpperCase() }} 轴</span>
          <strong>{{ device?.[axis] ?? '--' }}</strong>
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="section-heading chart-heading">
        <h2>三轴加速度实时曲线</h2>
        <span>最近 60 条上报数据</span>
      </div>
      <div ref="chartEl" class="chart" role="img" aria-label="最近 60 条设备上报的 X、Y、Z 三轴加速度曲线"></div>
      <p class="chart-note">按设备上报顺序展示，时间间隔不一定均匀；撤防消息不计入曲线。</p>
    </section>

    <section class="panel">
      <div class="section-heading">
        <h2>姿态检测指标</h2>
      </div>

      <div class="metrics">
        <div>
          <span>BASE · 基准偏移</span>
          <strong>{{ device?.base ?? '--' }}</strong>
        </div>

        <div>
          <span>CHANGE · 瞬时变化</span>
          <strong>{{ device?.change ?? '--' }}</strong>
        </div>
      </div>
    </section>

    <EventHistory />

    <footer>
      <span>
        {{ loading ? '正在加载设备数据…' : '最近上报：' + lastReceived }}
      </span>
      <span>V5.3 · Real-time Dashboard</span>
    </footer>
  </main>
</template>

<style scoped>
.dashboard {
  max-width: 1160px;
  margin: 0 auto;
  padding: 40px 24px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  margin-bottom: 32px;
}

.eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 2px;
}

h1 {
  font-size: 30px;
  margin: 8px 0;
}

.subtitle, .label, .section-heading span,
.card p, footer {
  color: #64748b;
}

.connection {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 12px 16px;
  border-radius: 999px;
  background: #fee2e2;
  color: #991b1b;
  white-space: nowrap;
}

.connection.connected {
  background: #dcfce7;
  color: #166534;
}

.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: currentColor;
}

.cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
  margin-bottom: 18px;
}

.card, .panel {
  background: white;
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  padding: 26px;
  box-shadow: 0 4px 20px #0f172a06;
}

.card h2 {
  font-size: 28px;
  margin: 16px 0 12px;
}

.card p {
  font-size: 13px;
  margin-bottom: 0;
}

.device-state.normal { color: #16a34a; }
.device-state.tilt { color: #d97706; }
.device-state.moving { color: #2563eb; }
.device-state.impact { color: #dc2626; }
.device-state.disarmed { color: #64748b; }

.panel {
  margin-bottom: 18px;
}

.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.section-heading h2 {
  margin: 0;
  font-size: 19px;
}

.axes, .metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.axis {
  padding: 28px 18px;
  text-align: center;
  background: #f1f5f9;
  border-radius: 12px;
}

.axis span, .metrics span {
  display: block;
  color: #64748b;
  font-size: 14px;
  margin-bottom: 12px;
}

.axis strong {
  font-size: 35px;
  font-variant-numeric: tabular-nums;
}

.metrics {
  grid-template-columns: repeat(2, 1fr);
}

.metrics > div {
  padding: 18px;
  background: #f8fafc;
  border-radius: 10px;
}

.metrics strong {
  font-size: 26px;
}

.error {
  padding: 12px;
  color: #991b1b;
  background: #fee2e2;
  border-radius: 8px;
}

footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  margin-top: 24px;
}

@media (max-width: 720px) {
  .header { flex-direction: column; align-items: flex-start; }
  .cards { grid-template-columns: 1fr; }
  .dashboard { padding: 20px 14px; }
  .axis { padding: 18px 8px; }
  .axis strong { font-size: 24px; }
}
.chart {
  width: 100%;
  height: 380px;
}

.chart-note {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.impact-alert {
  padding: 18px 22px;
  margin-bottom: 20px;
  background: #fef2f2;
  border: 1px solid #fca5a5;
  border-left: 5px solid #dc2626;
  border-radius: 12px;
  color: #991b1b;
}

.impact-alert strong { font-size: 18px; }
.impact-alert p { margin: 8px 0 0; }

@media (max-width: 720px) {
  .chart-heading { flex-wrap: wrap; gap: 8px; }
  .chart-heading span { font-size: 12px; }
}
</style>
