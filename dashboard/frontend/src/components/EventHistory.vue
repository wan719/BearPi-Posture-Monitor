<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'

const events = ref([])
const selectedState = ref('ALL')
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')
const lastUpdated = ref('--')

const states = ['ALL', 'NORMAL', 'MOVING', 'TILT', 'IMPACT', 'DISARMED']

let refreshTimer = null
let requestTimeout = null
let requestController = null
let stopped = false

// 统计范围：最近最多100条事件，不代表数据库全部记录
const impactCount = computed(() =>
    events.value.filter(e => e.state === 'IMPACT').length
)

const filteredEvents = computed(() =>
    selectedState.value === 'ALL'
        ? events.value
        : events.value.filter(e => e.state === selectedState.value)
)

function formatTime(value) {
    if (!value) return '--'

    const date = new Date(value)
    return Number.isNaN(date.getTime())
        ? '--'
        : date.toLocaleString('zh-CN', { hour12: false })
}

async function fetchEvents() {
    if (stopped || refreshing.value) return
    refreshing.value = true
    const controller = new AbortController()
    requestController = controller
    let timedOut = false
    requestTimeout = setTimeout(() => {
        timedOut = true
        controller.abort()
    }, 10000)

    try {
        const response = await fetch('/api/device/events?limit=100', {
            cache: 'no-store',
            signal: controller.signal
        })
        if (!response.ok) throw new Error(`HTTP ${response.status}`)

        const data = await response.json()
        if (!Array.isArray(data)) throw new Error('历史事件响应不是数组')
        if (data.some(item => !item || typeof item !== 'object' || Array.isArray(item))) {
            throw new Error('历史事件记录格式不正确')
        }
        if (stopped) return

        // 后端按时间倒序返回；统计与筛选只使用本次加载的最多 100 条。
        events.value = data.slice(0, 100)
        error.value = ''
        lastUpdated.value = formatTime(new Date())
    } catch (e) {
        if (!stopped) {
            error.value = timedOut
                ? '历史事件请求超时，请稍后重试。'
                : `历史事件加载失败：${e.message}`
        }
    } finally {
        clearTimeout(requestTimeout)
        requestTimeout = null
        requestController = null
        if (!stopped) {
            refreshing.value = false
            loading.value = false
        }
    }
}

onMounted(() => {
    fetchEvents()
    refreshTimer = setInterval(fetchEvents, 5000)
})

onUnmounted(() => {
    stopped = true
    clearInterval(refreshTimer)
    clearTimeout(requestTimeout)
    requestController?.abort()
})
</script>

<template>
    <section class="history-panel" aria-labelledby="history-heading" :aria-busy="loading">
        <div class="history-header">
            <div>
                <h2 id="history-heading">历史事件记录</h2>
                <p>最近最多 100 条状态变化 · 每 5 秒自动刷新</p>
            </div>

            <button type="button" class="refresh-button" :disabled="refreshing" @click="fetchEvents">
                {{ refreshing ? '刷新中...' : '刷新记录' }}
            </button>
        </div>

        <div class="history-stats">
            <div class="stat">
                <span>已加载事件</span>
                <strong>{{ events.length }}</strong>
            </div>

            <div class="stat">
                <span>IMPACT 次数（已加载记录）</span>
                <strong class="danger">{{ impactCount }}</strong>
            </div>

            <div class="stat">
                <span>最近成功刷新（本地时间）</span>
                <strong class="stat-time">{{ lastUpdated }}</strong>
            </div>
        </div>

        <p class="stats-note">统计仅针对已加载的最多 100 条记录，不代表数据库全部事件；状态筛选不改变统计范围。</p>

        <div class="toolbar">
            <label for="eventState">状态筛选</label>

            <select id="eventState" v-model="selectedState">
                <option v-for="state in states" :key="state" :value="state">
                    {{ state === 'ALL' ? '全部状态' : state }}
                </option>
            </select>

            <span>筛选结果：{{ filteredEvents.length }} 条</span>
        </div>

        <p v-if="error" class="error" role="alert">
            {{ error }}
            <span v-if="events.length">当前保留上次成功加载的记录。</span>
        </p>

        <p v-if="loading" class="empty">正在加载历史事件...</p>

        <p v-else-if="error && events.length === 0" class="empty">尚未取得历史记录，请刷新重试。</p>

        <p v-else-if="filteredEvents.length === 0" class="empty">
            {{ events.length ? '暂无符合筛选条件的事件' : '暂无历史事件，等待设备状态变化。' }}
        </p>

        <div v-else class="event-list" role="region" aria-label="历史事件列表" tabindex="0">
            <article v-for="event in filteredEvents" :key="event.id" class="event-item">
                <div class="event-top">
                    <span :class="['state-badge', event.state?.toLowerCase()]">
                        {{ event.state }}
                    </span>

                    <span class="event-time">
                        {{ formatTime(event.receivedAt) }}
                    </span>
                </div>

                <div class="transition">
                    {{ event.previousState ?? '首次上报' }}
                    <span>→</span>
                    <strong>{{ event.state }}</strong>
                </div>

                <div class="event-detail">
                    <span>X: {{ event.x }}</span>
                    <span>Y: {{ event.y }}</span>
                    <span>Z: {{ event.z }}</span>
                    <span>BASE: {{ event.base }}</span>
                    <span>CHANGE: {{ event.change }}</span>
                    <span>温度: {{ event.temperature }} °C</span>
                </div>

                <div class="event-bottom">
                    <span>事件 #{{ event.id }}</span>
                    <span>布防: {{ event.armed ? 'ARMED' : 'DISARMED' }}</span>
                </div>
            </article>
        </div>
    </section>
</template>

<style scoped>
.history-panel {
    margin-bottom: 18px;
    padding: 26px;
    background: white;
    border: 1px solid #e2e8f0;
    border-radius: 16px;
    box-shadow: 0 4px 20px #0f172a06;
}

.history-header,
.toolbar,
.event-top,
.event-bottom {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 14px;
}

.history-header h2 {
    margin: 0;
    font-size: 20px;
}

.history-header p,
.stat span,
.stats-note,
.event-time,
.event-bottom,
.toolbar {
    color: #64748b;
    font-size: 13px;
}

.refresh-button,
select {
    border: 1px solid #cbd5e1;
    border-radius: 8px;
    background: #f8fafc;
    padding: 9px 13px;
    cursor: pointer;
}

.refresh-button:disabled {
    cursor: wait;
    opacity: .6;
}

.history-stats {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 14px;
    margin: 24px 0 12px;
}

.stat {
    background: #f1f5f9;
    border-radius: 12px;
    padding: 20px;
}

.stat span {
    display: block;
    margin-bottom: 10px;
}

.stat strong {
    display: block;
    font-size: 28px;
}

.stat strong.danger {
    color: #dc2626;
}

.stat strong.stat-time {
    font-size: 15px;
    line-height: 1.6;
    overflow-wrap: anywhere;
}

.stats-note { margin: 0 0 20px; line-height: 1.6; }
.stat { min-width: 0; }
.refresh-button:focus-visible, select:focus-visible, .event-list:focus-visible {
    outline: 2px solid #2563eb;
    outline-offset: 3px;
}

.toolbar {
    justify-content: flex-start;
    margin-bottom: 20px;
    flex-wrap: wrap;
}

.toolbar>span {
    margin-left: auto;
}

.event-list {
    display: grid;
    gap: 12px;
    max-height: 620px;
    overflow-y: auto;
}

.event-item {
    padding: 18px;
    border: 1px solid #e2e8f0;
    border-radius: 12px;
    background: #fff;
}

.state-badge {
    padding: 5px 10px;
    font-size: 12px;
    font-weight: 700;
    border-radius: 7px;
    background: #f1f5f9;
    color: #475569;
}

.state-badge.normal {
    background: #dcfce7;
    color: #166534;
}

.state-badge.moving {
    background: #dbeafe;
    color: #1d4ed8;
}

.state-badge.tilt {
    background: #fef3c7;
    color: #92400e;
}

.state-badge.impact {
    background: #fee2e2;
    color: #b91c1c;
}

.state-badge.disarmed {
    background: #e2e8f0;
    color: #475569;
}

.transition {
    margin: 16px 0;
    font-size: 17px;
}

.transition span {
    padding: 0 8px;
    color: #94a3b8;
}

.event-detail {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    font-size: 12px;
    color: #475569;
}

.event-detail span {
    padding: 6px 9px;
    background: #f8fafc;
    border-radius: 6px;
}

.event-bottom {
    margin-top: 15px;
}

.error {
    color: #dc2626;
}

.empty {
    text-align: center;
    padding: 30px;
    color: #64748b;
}

@media (max-width: 720px) {
    .history-panel {
        padding: 16px;
    }

    .history-stats {
        grid-template-columns: 1fr;
    }

    .history-header {
        align-items: flex-start;
        flex-direction: column;
    }

    .event-top, .event-bottom { flex-wrap: wrap; }
    .event-time { overflow-wrap: anywhere; }
    .transition { overflow-wrap: anywhere; line-height: 1.6; }
    .toolbar > span { margin-left: 0; }
}
</style>
