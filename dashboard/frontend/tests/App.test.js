import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ref, computed } from 'vue'
import * as echarts from 'echarts'

// Exercise the actual App setup with real Vue reactivity and ECharts SVG rendering.
// Only browser IO and time are controlled; no backend/device data is changed.
function mountApp(initialStatus = null) {
  let time = 20000
  let mount, unmount
  const charts = []
  const sockets = []
  const listeners = new Map()
  const intervals = new Set()
  const timeouts = new Map()
  const source = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .* from .*$/gm, '')
  class Socket {
    constructor() { sockets.push(this) }
    close() { this.onclose?.() }
  }
  const api = runInNewContext(source + `\n;({ updateDevice, history, showImpactAlert, now, device, connectWebSocket, loadLatest })`, {
    ref, computed,
    onMounted: fn => { mount = fn },
    onUnmounted: fn => { unmount = fn },
    echarts: { init: () => {
      const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 800, height: 380 })
      charts.push(chart)
      return chart
    } },
    window: {
      location: { protocol: 'http:', host: 'localhost:5173' },
      addEventListener: (name, fn) => listeners.set(name, fn),
      removeEventListener: name => listeners.delete(name)
    },
    Date: class extends Date { static now() { return time } },
    fetch: async () => ({ status: initialStatus ? 200 : 204, ok: true, json: async () => initialStatus }),
    WebSocket: Socket,
    setInterval: fn => { intervals.add(fn); return fn },
    clearInterval: fn => intervals.delete(fn),
    setTimeout: (fn, delay) => { timeouts.set(fn, time + delay); return fn },
    clearTimeout: fn => timeouts.delete(fn),
    console
  })
  mount()
  return { api, charts, sockets, listeners, intervals, timeouts, unmount,
    tick(value) {
      time = value
      for (const fn of intervals) fn()
      for (const [fn, due] of timeouts) if (due <= time) { timeouts.delete(fn); fn() }
    }
  }
}
const sample = (n, state = 'NORMAL') => ({ armed: true, state, x: n, y: -n, z: 1500, receivedAt: new Date(1700000000000 + n * 1000).toISOString() })

test('mount renders three series, keeps newest 60 points and disposes resources', () => {
  const app = mountApp()
  try {
    assert.equal(app.charts.length, 1, 'chart must initialize on mount')
    for (let i = 1; i <= 65; i++) app.api.updateDevice(sample(i))
    const option = app.charts[0].getOption()
    assert.equal(option.series.length, 3)
    assert.equal(option.series[0].data.length, 60)
    assert.equal(option.series[0].data[0], 6)
    assert.equal(option.series[1].data[59], -65)
    assert.equal(option.series[2].data[59], 1500)
    assert.ok(app.charts[0].renderToSVGString().includes('<path'))
  } finally { app.unmount() }
  assert.ok(app.charts[0].isDisposed())
  assert.equal(app.listeners.size, 0)
  assert.equal(app.intervals.size, 0)
  assert.equal(app.timeouts.size, 0)
})

test('replayed IMPACT does not extend the ten-second alert or duplicate a point', () => {
  const app = mountApp()
  try {
    app.api.updateDevice(sample(1, 'IMPACT'))
    assert.equal(app.api.showImpactAlert.value, true)
    app.tick(29000)
    app.api.updateDevice(sample(1, 'IMPACT'))
    assert.equal(app.api.history.length, 1)
    app.tick(30000)
    assert.equal(app.api.showImpactAlert.value, false)
    app.api.updateDevice(sample(2, 'IMPACT'))
    assert.equal(app.api.showImpactAlert.value, true)
  } finally { app.unmount() }
})

test('disarmed status updates cards without adding acceleration samples', () => {
  const app = mountApp()
  try {
    app.api.updateDevice({ ...sample(1), armed: false, state: 'DISARMED' })
    assert.equal(app.api.device.value.state, 'DISARMED')
    assert.equal(app.api.history.length, 0)
    assert.equal(app.api.showImpactAlert.value, false)
  } finally { app.unmount() }
})

test('REST seeds one point, WebSocket updates it and reconnect replay stays deduplicated', async () => {
  const app = mountApp(sample(1))
  try {
    await app.api.loadLatest()
    assert.equal(app.api.history.length, 1)
    assert.equal(app.charts[0].getOption().series[0].data[0], 1)
    assert.equal(app.charts[0].getOption().series[0].showSymbol, true)
    app.sockets[0].onmessage({ data: JSON.stringify(sample(2)) })
    assert.equal(app.api.history.length, 2)
    assert.equal(app.api.device.value.x, 2)
    await app.api.loadLatest()
    assert.equal(app.api.device.value.x, 2, 'delayed REST must not replace newer live state')
    app.sockets[0].close()
    app.tick(23000)
    assert.equal(app.sockets.length, 2)
    app.sockets[1].onopen()
    app.sockets[1].onmessage({ data: JSON.stringify(sample(2)) })
    await Promise.resolve()
    assert.equal(app.api.history.length, 2)
    app.sockets[1].onmessage({ data: JSON.stringify(sample(3)) })
    assert.equal(app.charts[0].getOption().series[0].data[2], 3)
  } finally { app.unmount() }
})