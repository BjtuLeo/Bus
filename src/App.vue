<script setup>
import L from 'leaflet'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

const navItems = ['线路总览', '实时地图', '到站提醒']

const currentCity = ref('北京')
const cityItems = [
  { name: '北京', type: 'city' },
  { name: '伦敦', type: 'city' },
  { name: '收藏线路', type: 'menu' },
  { name: '提醒中心', type: 'menu' }
]

const searchQuery = ref('')
const currentLineId = ref('')
const lineSuggestions = ref([])
const isSuggestionOpen = ref(false)
const selectedLine = ref(null)
const searchTimer = ref(null)

const mapElement = ref(null)
const leafletMap = ref(null)
const londonMapReady = ref(false)
const routeLayerGroup = ref(null)

const currentLineMap = ref(null)
const lineStatus = ref('')
const lineStatusMessage = ref('')
const lineMapUpdatedAt = ref(Date.now())
const countdownNow = ref(Date.now())

const activeDirectionId = ref('')
const expandedStopId = ref('')
const reminderForms = ref({})
const reminderTasks = ref([])
const reminderToasts = ref([])
const isLinePanelOpen = ref(false)

const LINE_REFRESH_MS = 25000
const COUNTDOWN_TICK_MS = 1000

let lineRefreshTimer = null
let countdownTimer = null
const stopMarkerLookup = new Map()

const currentMapMeta = computed(() => {
  if (currentCity.value === '伦敦') {
    return {
      badge: '伦敦实时地图',
      copy: '地图展示当前选中线路的静态轨迹与站点，实时到站信息在右侧站点面板中查看。'
    }
  }

  return {
    badge: '北京地图接口预留区域',
    copy: '当前为北京地图占位视图，后续可接入北京实时公交线路、站点与到站提醒数据。'
  }
})

const directionOptions = computed(() => {
  const directions = currentLineMap.value?.directions
  if (Array.isArray(directions) && directions.length > 0) {
    return directions
  }

  const fallbackStops = Array.isArray(currentLineMap.value?.stops) ? currentLineMap.value.stops : []
  if (!fallbackStops.length) {
    return []
  }

  return [
    {
      id: 'default',
      label: formatDirectionLabel(fallbackStops.at(-1)?.name || '终点站'),
      destinationName: fallbackStops.at(-1)?.name || '终点站',
      stops: fallbackStops
    }
  ]
})

const activeDirection = computed(() => {
  const directions = directionOptions.value
  if (!directions.length) {
    return null
  }

  return directions.find((item) => item.id === activeDirectionId.value) || directions[0]
})

const activeStops = computed(() => activeDirection.value?.stops || [])

const expandedStop = computed(() => activeStops.value.find((stop) => stop.id === expandedStopId.value) || null)

const reminderTaskCount = computed(() => reminderTasks.value.length)
const activeLineColor = computed(() => getLineColor(currentLineId.value || selectedLine.value?.id || '52'))

function selectSidebarItem(item) {
  if (item.type === 'city') {
    currentCity.value = item.name
  }
}

function normalizeLineId(value) {
  return String(value || '').trim().toUpperCase()
}

function extractLineId(value) {
  const raw = normalizeLineId(value)
  if (!raw) {
    return ''
  }

  const matched = raw.match(/[A-Z]*\d+[A-Z]*/i)
  return matched ? normalizeLineId(matched[0]) : raw.replace(/\s+/g, '')
}

function buildLineItem(lineId) {
  const normalized = normalizeLineId(lineId)
  return {
    id: normalized,
    name: normalized,
    displayName: `${normalized}路公交`,
    city: '伦敦',
    mode: 'bus'
  }
}

function getLineColor(lineId) {
  const palette = ['#d92d27', '#1769ff', '#0f9d58', '#8e24aa', '#ff6d00', '#00838f', '#c2185b', '#5d4037']
  const normalized = normalizeLineId(lineId)
  if (!normalized) {
    return '#d92d27'
  }

  let hash = 0
  for (const char of normalized) {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  }
  return palette[hash % palette.length]
}

function formatDirectionLabel(destinationName) {
  return `开往: ${destinationName || '终点站'}`
}

async function ensureLondonMap() {
  if (leafletMap.value) {
    leafletMap.value.invalidateSize()
    return
  }

  await nextTick()
  if (!mapElement.value) {
    return
  }

  const map = L.map(mapElement.value, {
    zoomControl: false,
    attributionControl: true
  }).setView([51.5074, -0.1278], 12)

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map)

  leafletMap.value = map
  londonMapReady.value = true
  map.invalidateSize()
}

function clearRouteLayer() {
  if (routeLayerGroup.value) {
    routeLayerGroup.value.clearLayers()
  }
  stopMarkerLookup.clear()
}

function buildStopPopup(stop) {
  return `
    <div class="stop-popup">
      <strong>${stop.name}</strong>
      <div>点击右侧站点卡片可查看最近两辆车到站信息</div>
    </div>
  `
}

function renderLineOnMap(lineMap, shouldFit = true) {
  if (!leafletMap.value) {
    return
  }

  clearRouteLayer()

  if (!routeLayerGroup.value) {
    routeLayerGroup.value = L.layerGroup().addTo(leafletMap.value)
  }

  const layers = []
  const routePath = (lineMap.routePath || []).map((point) => [point.lat, point.lng])
  if (routePath.length > 1) {
    const routeLine = L.polyline(routePath, {
      color: activeLineColor.value,
      weight: 6,
      opacity: 0.96,
      lineJoin: 'round'
    })
    routeLayerGroup.value.addLayer(routeLine)
    layers.push(routeLine)
  }

  ;(lineMap.stops || []).forEach((stop) => {
    const marker = L.circleMarker([stop.lat, stop.lng], {
      radius: 5,
      color: activeLineColor.value,
      weight: 3,
      fillColor: '#ffffff',
      fillOpacity: 1
    }).bindPopup(buildStopPopup(stop))

    stopMarkerLookup.set(stop.id, marker)
    routeLayerGroup.value.addLayer(marker)
    layers.push(marker)
  })

  if (layers.length) {
    const bounds = L.featureGroup(layers).getBounds()
    if (shouldFit && bounds.isValid()) {
      leafletMap.value.fitBounds(bounds, {
        padding: [70, 70]
      })
    }
  }
}

function getDirectionBadge(index) {
  return index + 1 < 10 ? `0${index + 1}` : `${index + 1}`
}

function ensureActiveDirection() {
  const directions = directionOptions.value
  if (!directions.length) {
    activeDirectionId.value = ''
    expandedStopId.value = ''
    return
  }

  const stillExists = directions.some((item) => item.id === activeDirectionId.value)
  if (!stillExists) {
    activeDirectionId.value = directions[0].id
  }

  if (!activeStops.value.some((stop) => stop.id === expandedStopId.value)) {
    expandedStopId.value = ''
  }
}

function getPredictionRemainingSeconds(prediction) {
  const source = Number(prediction?.timeToStationSeconds)
  if (!Number.isFinite(source)) {
    return null
  }

  const elapsedSeconds = Math.floor((countdownNow.value - lineMapUpdatedAt.value) / 1000)
  return Math.max(0, source - elapsedSeconds)
}

function getArrivalPresentation(remainingSeconds) {
  if (remainingSeconds == null) {
    return {
      label: '暂无预测',
      state: 'muted'
    }
  }

  if (remainingSeconds <= 5) {
    return {
      label: '已到站',
      state: 'arrived'
    }
  }

  if (remainingSeconds <= 30) {
    return {
      label: '即将到站',
      state: 'soon'
    }
  }

  return {
    label: `${Math.ceil(remainingSeconds / 60)} 分钟`,
    state: 'normal'
  }
}

function getStopPredictions(stop) {
  return (stop.arrivals || []).slice(0, 2).map((prediction, index) => {
    const remainingSeconds = getPredictionRemainingSeconds(prediction)
    const presentation = getArrivalPresentation(remainingSeconds)
    return {
      key: `${stop.id}-${prediction.vehicleId || index}`,
      vehicleId: prediction.vehicleId,
      destinationName: formatDirectionLabel(prediction.destinationName || activeDirection.value?.destinationName || '终点站'),
      remainingSeconds,
      label: index === 0 ? '下一辆' : '下下辆',
      display: presentation.label,
      state: presentation.state
    }
  })
}

function getReminderForm(directionId, stopId) {
  const key = `${directionId}:${stopId}`
  if (!reminderForms.value[key]) {
    reminderForms.value[key] = {
      stopsAhead: 1,
      minutesAhead: 3
    }
  }
  return reminderForms.value[key]
}

function focusStopOnMap(stop) {
  if (!leafletMap.value || !stop) {
    return
  }

  leafletMap.value.flyTo([stop.lat, stop.lng], Math.max(leafletMap.value.getZoom(), 14), {
    animate: true,
    duration: 0.8
  })

  const marker = stopMarkerLookup.get(stop.id)
  if (marker) {
    marker.openPopup()
  }
}

function toggleStop(stop) {
  expandedStopId.value = expandedStopId.value === stop.id ? '' : stop.id
  if (expandedStopId.value === stop.id) {
    focusStopOnMap(stop)
  }
}

function selectDirection(directionId) {
  activeDirectionId.value = directionId
  expandedStopId.value = ''

  const firstStop = activeDirection.value?.stops?.[0]
  if (firstStop) {
    focusStopOnMap(firstStop)
  }
}

function pushReminderToast(task, message) {
  const toast = {
    id: `${task.id}-${Date.now()}`,
    title: `${selectedLine.value?.displayName || '公交线路'} 到站提醒`,
    message
  }
  reminderToasts.value = [toast, ...reminderToasts.value].slice(0, 3)

  window.setTimeout(() => {
    dismissToast(toast.id)
  }, 5000)
}

function dismissToast(toastId) {
  reminderToasts.value = reminderToasts.value.filter((item) => item.id !== toastId)
}

function enableReminder(stop) {
  const direction = activeDirection.value
  if (!direction) {
    return
  }

  const form = getReminderForm(direction.id, stop.id)
  const taskId = `${selectedLine.value?.id || 'line'}:${direction.id}:${stop.id}`
  const nextPredictions = getStopPredictions(stop)

  reminderTasks.value = reminderTasks.value.filter((item) => item.id !== taskId)
  const task = {
    id: taskId,
    lineId: selectedLine.value?.id || '',
    lineName: selectedLine.value?.displayName || '',
    directionId: direction.id,
    directionLabel: direction.label,
    stopId: stop.id,
    stopName: stop.name,
    stopsAhead: Number(form.stopsAhead) || 1,
    minutesAhead: Number(form.minutesAhead) || 3,
    targetVehicleIds: nextPredictions.map((item) => item.vehicleId).filter(Boolean),
    triggered: false
  }
  reminderTasks.value.push(task)

  pushReminderToast(
    task,
    `${stop.name} 的到站提醒已开启，将在提前 ${task.stopsAhead} 站或提前 ${task.minutesAhead} 分钟时提示。`
  )
}

function resetLineState() {
  stopLiveTimers()
  clearRouteLayer()
  currentLineMap.value = null
  lineStatus.value = ''
  lineStatusMessage.value = ''
  activeDirectionId.value = ''
  expandedStopId.value = ''
  reminderTasks.value = []
  reminderToasts.value = []
  reminderForms.value = {}
  lineMapUpdatedAt.value = Date.now()
  countdownNow.value = Date.now()
}

function findReminderStop(directionId, stopId) {
  const direction = directionOptions.value.find((item) => item.id === directionId)
  if (!direction) {
    return null
  }

  return direction.stops.find((item) => item.id === stopId) || null
}

function matchesStopsAhead(task, directionStops, targetIndex) {
  if (task.stopsAhead <= 0) {
    return false
  }

  const upstreamStops = directionStops.slice(Math.max(0, targetIndex - task.stopsAhead), targetIndex)
  if (!upstreamStops.length) {
    return false
  }

  if (task.targetVehicleIds.length) {
    return upstreamStops.some((stop) =>
      (stop.arrivals || []).some((prediction) => task.targetVehicleIds.includes(prediction.vehicleId))
    )
  }

  return upstreamStops.some((stop) => (stop.arrivals || []).length > 0)
}

function evaluateReminders() {
  if (!reminderTasks.value.length) {
    return
  }

  reminderTasks.value.forEach((task) => {
    if (task.triggered || task.lineId !== selectedLine.value?.id) {
      return
    }

    const direction = directionOptions.value.find((item) => item.id === task.directionId)
    if (!direction) {
      return
    }

    const stopIndex = direction.stops.findIndex((item) => item.id === task.stopId)
    if (stopIndex < 0) {
      return
    }

    const stop = direction.stops[stopIndex]
    const predictions = getStopPredictions(stop)
    const minutesThresholdSeconds = task.minutesAhead * 60
    const matchedByMinutes = predictions.some((item) => item.remainingSeconds != null && item.remainingSeconds <= minutesThresholdSeconds)
    const matchedByStops = matchesStopsAhead(task, direction.stops, stopIndex)

    if (!matchedByMinutes && !matchedByStops) {
      return
    }

    task.triggered = true
    const message = matchedByStops
      ? `${task.stopName} 的车辆已进入前 ${task.stopsAhead} 站范围，请准备下车。`
      : `${task.stopName} 最近一辆公交将在 ${task.minutesAhead} 分钟内到站。`
    pushReminderToast(task, message)
  })
}

async function fetchSuggestions(query) {
  try {
    const response = await fetch(`/api/lines/search?q=${encodeURIComponent(query)}`)
    if (!response.ok) {
      lineSuggestions.value = []
      isSuggestionOpen.value = false
      return
    }

    const result = await response.json()
    lineSuggestions.value = result
    isSuggestionOpen.value = result.length > 0
  } catch {
    lineSuggestions.value = []
    isSuggestionOpen.value = false
  }
}

function stopLiveTimers() {
  if (lineRefreshTimer) {
    clearInterval(lineRefreshTimer)
    lineRefreshTimer = null
  }

  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

function startLiveTimers() {
  stopLiveTimers()

  lineRefreshTimer = setInterval(() => {
    loadSelectedLineMap(true)
  }, LINE_REFRESH_MS)

  countdownTimer = setInterval(() => {
    countdownNow.value = Date.now()
    evaluateReminders()
  }, COUNTDOWN_TICK_MS)
}

async function selectLine(line) {
  const normalizedLineId = normalizeLineId(line.id)
  selectedLine.value = {
    ...line,
    id: normalizedLineId,
    displayName: line.displayName || `${normalizedLineId}路公交`
  }
  searchQuery.value = normalizedLineId
  isSuggestionOpen.value = false
  isLinePanelOpen.value = true
  currentLineId.value = normalizedLineId
}

function closeLinePanel() {
  isLinePanelOpen.value = false
  expandedStopId.value = ''
}

async function submitLineSearch() {
  const lineId = extractLineId(searchQuery.value)
  if (!lineId) {
    return
  }

  isSuggestionOpen.value = false
  isLinePanelOpen.value = true
  selectedLine.value = buildLineItem(lineId)
  searchQuery.value = lineId
  currentLineId.value = lineId
}

async function loadSelectedLineMap(refreshOnly = false) {
  if (!currentLineId.value) {
    return
  }

  try {
    const response = await fetch(`/api/lines/${encodeURIComponent(currentLineId.value)}/map`)
    if (!response.ok) {
      return
    }

    const lineMap = await response.json()
    selectedLine.value = {
      id: normalizeLineId(lineMap.id || currentLineId.value),
      name: lineMap.name || currentLineId.value,
      displayName: lineMap.displayName || `${currentLineId.value}路公交`,
      city: lineMap.city || '伦敦',
      mode: 'bus'
    }
    currentLineMap.value = lineMap
    lineStatus.value = lineMap.vehicleStatus || ''
    lineStatusMessage.value = lineMap.vehicleMessage || ''
    lineMapUpdatedAt.value = Date.now()
    countdownNow.value = lineMapUpdatedAt.value
    ensureActiveDirection()

    if (!refreshOnly) {
      renderLineOnMap(lineMap, true)
    }

    evaluateReminders()
  } catch {
    if (!refreshOnly) {
      clearRouteLayer()
    }
    lineStatus.value = 'error'
    lineStatusMessage.value = '请求线路与到站信息失败，请检查后端或 TfL 接口连通性'
  }
}

watch(
  currentCity,
  async (city) => {
    if (city === '伦敦') {
      await ensureLondonMap()
      if (currentLineId.value) {
        await loadSelectedLineMap(false)
        startLiveTimers()
      }
      return
    }

    stopLiveTimers()
    clearRouteLayer()
    if (leafletMap.value) {
      leafletMap.value.invalidateSize()
    }
  },
  { immediate: true }
)

watch(currentLineId, async (newLineId, oldLineId) => {
  const normalizedNew = normalizeLineId(newLineId)
  const normalizedOld = normalizeLineId(oldLineId)
  if (normalizedNew === normalizedOld) {
    return
  }

  resetLineState()

  if (!normalizedNew) {
    selectedLine.value = null
    isLinePanelOpen.value = false
    return
  }

  selectedLine.value = buildLineItem(normalizedNew)

  if (currentCity.value !== '伦敦') {
    currentCity.value = '伦敦'
    return
  }

  await ensureLondonMap()
  await loadSelectedLineMap(false)
  startLiveTimers()
})

watch(directionOptions, () => {
  ensureActiveDirection()
})

watch(searchQuery, (query) => {
  if (searchTimer.value) {
    clearTimeout(searchTimer.value)
  }

  if (!query.trim()) {
    lineSuggestions.value = []
    isSuggestionOpen.value = false
    return
  }

  searchTimer.value = setTimeout(() => {
    fetchSuggestions(query.trim())
  }, 220)
})

onBeforeUnmount(() => {
  if (searchTimer.value) {
    clearTimeout(searchTimer.value)
  }
  stopLiveTimers()
  clearRouteLayer()
  if (leafletMap.value) {
    leafletMap.value.remove()
    leafletMap.value = null
  }
})
</script>

<template>
  <div class="app-shell">
    <div class="map-canvas" :class="`map-canvas-${currentCity}`">
      <div
        v-show="currentCity === '伦敦'"
        ref="mapElement"
        class="leaflet-map-layer"
        :class="{ visible: londonMapReady }"
      ></div>
      <div class="map-overlay map-overlay-city"></div>
      <div class="map-grid map-grid-a"></div>
      <div class="map-grid map-grid-b"></div>
      <div class="map-routes"></div>
      <div class="map-nodes"></div>
      <div class="map-badge">{{ currentMapMeta.badge }}</div>
      <p class="map-copy">{{ currentMapMeta.copy }}</p>
    </div>

    <div class="ambient ambient-left"></div>
    <div class="ambient ambient-right"></div>

    <header class="topbar">
      <div class="brand">公交查询系统</div>

      <nav class="topnav">
        <a
          v-for="item in navItems"
          :key="item"
          href="#"
          :class="['topnav-link', { active: item === '线路总览' }]"
        >
          {{ item }}
        </a>
      </nav>

      <div class="topbar-actions">
        <button class="icon-button" type="button" aria-label="消息提醒">
          <span class="icon-bell"></span>
        </button>
        <button class="icon-button" type="button" aria-label="系统设置">
          <span class="icon-gear"></span>
        </button>
      </div>
    </header>

    <aside class="sidebar">
      <section class="sidebar-header">
        <h1>城市公交</h1>
        <p>公交到站查询与实时提醒服务</p>
        <div class="system-status">
          <span class="status-dot"></span>
          <span>系统状态：正常</span>
        </div>
      </section>

      <nav class="city-list">
        <button
          v-for="item in cityItems"
          :key="item.name"
          class="city-item"
          :class="{ active: item.type === 'city' && currentCity === item.name }"
          type="button"
          @click="selectSidebarItem(item)"
        >
          <span class="city-icon"></span>
          <span>{{ item.name }}</span>
        </button>
      </nav>
    </aside>

    <main class="floating-panel">
      <div class="search-card">
        <div class="search-shell">
          <div class="search-box">
            <span class="search-icon"></span>
            <input
              v-model="searchQuery"
              type="text"
              placeholder="输入公交线路、站点或目的地"
              @keydown.enter.prevent="submitLineSearch"
              @focus="isSuggestionOpen = lineSuggestions.length > 0"
              @blur="setTimeout(() => { isSuggestionOpen = false }, 150)"
            />
          </div>

          <div v-if="isSuggestionOpen" class="search-dropdown">
            <button
              v-for="item in lineSuggestions"
              :key="item.id"
              class="search-option"
              type="button"
              @click="selectLine(item)"
            >
              <span>{{ item.displayName }}</span>
              <small>{{ item.city }} · {{ item.mode }}</small>
            </button>
          </div>
        </div>

        <button class="primary-button" type="button" @click="submitLineSearch">
          <span class="icon-bell small"></span>
          搜索线路
        </button>
      </div>

      <div v-if="selectedLine" class="line-status-card" :class="lineStatus">
        <div>
          <strong>{{ selectedLine.displayName }}</strong>
          <span>{{ lineStatusMessage || '已切换为站点到站信息视图' }}</span>
        </div>
        <small>提醒任务 {{ reminderTaskCount }} 个</small>
      </div>
    </main>

    <aside v-if="selectedLine && isLinePanelOpen" class="line-sidepanel">
      <template v-if="activeDirection">
        <header class="line-panel-header">
          <div>
            <small>{{ selectedLine.city }} · {{ selectedLine.mode }}</small>
            <h2>{{ selectedLine.displayName }}</h2>
          </div>
          <div class="line-panel-header-actions">
            <span class="line-panel-chip">{{ activeStops.length }} 站</span>
            <button type="button" class="line-panel-close" @click="closeLinePanel">×</button>
          </div>
        </header>

        <div class="direction-tabs">
          <button
            v-for="(direction, index) in directionOptions"
            :key="direction.id"
            type="button"
            class="direction-tab"
            :class="{ active: direction.id === activeDirectionId }"
            @click="selectDirection(direction.id)"
          >
            <span class="direction-tab-index">{{ getDirectionBadge(index) }}</span>
            <span>{{ formatDirectionLabel(direction.destinationName || direction.label) }}</span>
          </button>
        </div>

        <div class="stop-list">
          <article
            v-for="(stop, index) in activeStops"
            :key="`${activeDirectionId}-${stop.id}-${index}`"
            class="stop-card"
            :class="{ expanded: expandedStopId === stop.id }"
          >
            <button class="stop-card-head" type="button" @click="toggleStop(stop)">
              <div class="stop-seq">
                <span class="stop-seq-dot" :style="{ background: activeLineColor, boxShadow: `0 0 14px ${activeLineColor}66` }"></span>
                <span class="stop-seq-index">{{ getDirectionBadge(index) }}</span>
              </div>

              <div class="stop-card-copy">
                <strong>{{ stop.name }}</strong>
                <span>{{ formatDirectionLabel(activeDirection.destinationName) }}</span>
              </div>

              <span class="stop-card-toggle">{{ expandedStopId === stop.id ? '收起' : '展开' }}</span>
            </button>

            <div v-if="expandedStopId === stop.id" class="stop-card-body">
              <div class="arrival-list">
                <div
                  v-for="item in getStopPredictions(stop)"
                  :key="item.key"
                  class="arrival-item"
                  :class="item.state"
                >
                  <div>
                    <small>{{ item.label }}</small>
                    <strong>{{ item.destinationName }}</strong>
                  </div>
                  <span class="arrival-time">{{ item.display }}</span>
                </div>

                <div v-if="getStopPredictions(stop).length === 0" class="arrival-empty">
                  当前暂无这座站点的到站预测数据
                </div>
              </div>

              <div class="reminder-box">
                <div class="reminder-box-header">
                  <strong>到站提醒</strong>
                  <span>页面内提示，不调用浏览器原生通知</span>
                </div>

                <div class="reminder-form">
                  <label class="reminder-field">
                    <span>提前站数</span>
                    <select v-model.number="getReminderForm(activeDirectionId, stop.id).stopsAhead">
                      <option :value="1">提前 1 站</option>
                      <option :value="2">提前 2 站</option>
                      <option :value="3">提前 3 站</option>
                    </select>
                  </label>

                  <label class="reminder-field">
                    <span>提前时间</span>
                    <select v-model.number="getReminderForm(activeDirectionId, stop.id).minutesAhead">
                      <option :value="1">1 分钟</option>
                      <option :value="3">3 分钟</option>
                      <option :value="5">5 分钟</option>
                    </select>
                  </label>
                </div>

                <button class="reminder-action" type="button" @click="enableReminder(stop)">
                  开启提醒
                </button>
              </div>
            </div>
          </article>
        </div>
      </template>

      <div v-else class="line-panel-empty">
        <strong>选择一条公交线路</strong>
        <p>搜索并选中线路后，这里会显示两个方向的站点序列与实时到站信息。</p>
      </div>
    </aside>

    <div v-if="reminderToasts.length" class="toast-stack">
      <div v-for="toast in reminderToasts" :key="toast.id" class="toast-card">
        <div>
          <strong>{{ toast.title }}</strong>
          <p>{{ toast.message }}</p>
        </div>
        <button type="button" class="toast-close" @click="dismissToast(toast.id)">知道了</button>
      </div>
    </div>
  </div>
</template>
