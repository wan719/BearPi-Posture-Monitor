import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ref, computed } from 'vue'

const settle = () => new Promise(resolve => setImmediate(resolve))
const event = (id, state = 'NORMAL') => ({ id, previousState: null, state,
  armed: true, x: 1, y: -2, z: 1500, base: 3, change: 4, temperature: 26,
  receivedAt: '2026-10-12T01:02:03Z' })

function mountHistory() {
  const requests = [], intervals = new Map(), timeouts = new Map()
  let mount, unmount
  const source = readFileSync(new URL('../src/components/EventHistory.vue', import.meta.url), 'utf8')
    .match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .* from .*$/gm, '')
  const api = runInNewContext(source + '\n;({ events, selectedState, loading, refreshing, error, lastUpdated, impactCount, filteredEvents, formatTime, fetchEvents })', {
    ref, computed, Date, AbortController,
    onMounted: fn => { mount = fn }, onUnmounted: fn => { unmount = fn },
    setInterval: (fn, delay) => { intervals.set(fn, delay); return fn },
    clearInterval: fn => intervals.delete(fn),
    setTimeout: (fn, delay) => { timeouts.set(fn, delay); return fn },
    clearTimeout: fn => timeouts.delete(fn),
    fetch: (url, options) => new Promise((resolve, reject) => {
      requests.push({ url, options, resolve, reject })
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    })
  })
  mount()
  return { api, requests, intervals, timeouts, unmount,
    async reply(data, status = 200) {
      requests.at(-1).resolve({ ok: status >= 200 && status < 300, status, json: async () => data })
      await settle()
    }
  }
}

test('caps records and IMPACT statistics at 100, independent of active filter', async () => {
  const app = mountHistory()
  try {
    assert.equal(app.requests[0].url, '/api/device/events?limit=100')
    await app.reply(Array.from({ length: 105 }, (_, i) => event(i, i % 2 ? 'NORMAL' : 'IMPACT')))
    assert.equal(app.api.events.value.length, 100)
    assert.equal(app.api.impactCount.value, 50)
    app.api.selectedState.value = 'NORMAL'
    assert.equal(app.api.filteredEvents.value.length, 50)
    assert.equal(app.api.impactCount.value, 50)
    assert.notEqual(app.api.lastUpdated.value, '--')
    assert.equal(app.api.loading.value, false)
  } finally { app.unmount() }
})

test('all six states filter locally and dates use the local timezone', async () => {
  const app = mountHistory()
  try {
    const states = ['NORMAL', 'MOVING', 'TILT', 'IMPACT', 'DISARMED']
    await app.reply(states.map((state, i) => event(i, state)))
    for (const state of states) {
      app.api.selectedState.value = state
      assert.equal(app.api.filteredEvents.value.length, 1)
      assert.equal(app.api.filteredEvents.value[0].state, state)
    }
    app.api.selectedState.value = 'ALL'
    assert.equal(app.api.filteredEvents.value.length, 5)
    assert.equal(app.api.formatTime('2026-10-12T01:02:03Z'), new Date('2026-10-12T01:02:03Z').toLocaleString('zh-CN', { hour12: false }))
    assert.equal(app.api.formatTime('bad-date'), '--')
    assert.equal(app.requests.length, 1)
  } finally { app.unmount() }
})

test('five-second polling and manual refresh cannot overlap a request', async () => {
  const app = mountHistory()
  try {
    const [poll, delay] = [...app.intervals][0]
    assert.equal(delay, 5000)
    poll(); app.api.fetchEvents()
    assert.equal(app.requests.length, 1)
    await app.reply([])
    assert.equal(app.api.events.value.length, 0)
    poll()
    assert.equal(app.requests.length, 2)
    await app.reply([event(1)])
    app.api.fetchEvents()
    assert.equal(app.requests.length, 3)
    await app.reply([])
  } finally { app.unmount() }
})

test('failed refresh keeps the last successful records and timestamp, then recovers', async () => {
  const app = mountHistory()
  try {
    await app.reply([event(1, 'IMPACT')])
    const lastUpdated = app.api.lastUpdated.value
    app.api.fetchEvents(); await app.reply(null, 503)
    assert.match(app.api.error.value, /503/)
    assert.equal(app.api.events.value.length, 1)
    assert.equal(app.api.lastUpdated.value, lastUpdated)
    app.api.fetchEvents(); await app.reply({ content: [] })
    assert.match(app.api.error.value, /数组/)
    assert.equal(app.api.events.value.length, 1)
    app.api.fetchEvents(); await app.reply([])
    assert.equal(app.api.error.value, '')
    assert.equal(app.api.events.value.length, 0)
  } finally { app.unmount() }
})

test('hung requests time out and allow later retries', async () => {
  const app = mountHistory()
  try {
    assert.equal(app.timeouts.size, 1)
    const [timeout, delay] = [...app.timeouts][0]
    assert.equal(delay, 10000)
    timeout(); await settle()
    assert.equal(app.api.refreshing.value, false)
    assert.match(app.api.error.value, /超时/)
    app.api.fetchEvents()
    assert.equal(app.requests.length, 2)
    await app.reply([])
    assert.equal(app.api.error.value, '')
  } finally { app.unmount() }
})

test('unmount aborts in-flight work and clears every timer without showing an error', async () => {
  const app = mountHistory()
  const signal = app.requests[0].options.signal
  app.unmount(); await settle()
  assert.ok(signal?.aborted)
  assert.equal(app.intervals.size, 0)
  assert.equal(app.timeouts.size, 0)
  assert.equal(app.api.error.value, '')
  app.api.fetchEvents()
  assert.equal(app.requests.length, 1)
})
