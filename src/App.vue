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

const routeStart = ref('')
const routeEnd = ref('')
const searchQuery = ref('')
const lineSuggestions = ref([])
const isSuggestionOpen = ref(false)
const selectedLine = ref(null)
const searchTimer = ref(null)
const mapElement = ref(null)
const leafletMap = ref(null)
const londonMapReady = ref(false)
const routeLayerGroup = ref(null)
const vehicleLayerGroup = ref(null)
const currentLineMap = ref(null)
const vehicleStatus = ref('')
const vehicleMessage = ref('')

const VEHICLE_REFRESH_MS = 25000
const STATION_HOLD_MS = 2500
let vehicleRefreshTimer = null
let vehicleAnimationFrame = null
const vehicleMarkers = new Map()

const currentMapMeta = computed(() => {
  if (currentCity.value === '伦敦') {
    return {
      badge: '伦敦实时地图',
      copy: '当前底图来自 OpenStreetMap，可在此继续叠加伦敦公交站点、车辆位置与线路覆盖层。'
    }
  }

  return {
    badge: '北京地图接口预留区域',
    copy: '当前为北京地图占位视图，后续可接入北京实时公交、车辆位置与站点覆盖层。'
  }
})

function swapRoutePoints() {
  const start = routeStart.value
  routeStart.value = routeEnd.value
  routeEnd.value = start
}

function selectSidebarItem(item) {
  if (item.type === 'city') {
    currentCity.value = item.name
  }
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
}

function clearVehicleLayer() {
  if (vehicleLayerGroup.value) {
    vehicleLayerGroup.value.clearLayers()
  }
  vehicleMarkers.clear()
}

function formatArrival(prediction) {
  if (!prediction?.timeToStationSeconds && prediction?.timeToStationSeconds !== 0) {
    return '暂无到站预测'
  }
  const minutes = Math.max(1, Math.round(prediction.timeToStationSeconds / 60))
  return `${minutes} 分钟后到达`
}

function buildPopup(stop) {
  const predictions = stop.arrivals?.length
    ? stop.arrivals
        .slice(0, 2)
        .map((item, index) => {
          const label = index === 0 ? '下一辆' : '下下辆'
          return `<div>${label}：${item.destinationName || '52路'} · ${formatArrival(item)}</div>`
        })
        .join('')
    : '<div>下一辆：暂无到站预测</div><div>下下辆：暂无到站预测</div>'

  return `
    <div class="stop-popup">
      <strong>${stop.name}</strong>
      <div>${predictions}</div>
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
      color: '#d92d27',
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
      color: '#d92d27',
      weight: 3,
      fillColor: '#ffffff',
      fillOpacity: 1
    }).bindPopup(buildPopup(stop))

    routeLayerGroup.value.addLayer(marker)
    layers.push(marker)
  })

  if (layers.length) {
    const bounds = L.featureGroup(layers).getBounds()
    if (shouldFit && bounds.isValid()) {
      leafletMap.value.fitBounds(bounds, {
        padding: [60, 60]
      })
    }
  }
}

function createVehicleIcon(lineName) {
  return createDirectionalVehicleIcon(lineName, 0)
}

function createDirectionalVehicleIcon(lineName, bearing = 0) {
  const safeBearing = Number.isFinite(bearing) ? bearing : 0

  return L.divIcon({
    className: 'bus-vehicle-wrapper',
    html: `
      <div class="bus-vehicle-chip">
      <div class="bus-vehicle-rotator" style="transform: rotate(${safeBearing}deg);">
        <span class="bus-vehicle-arrow"></span>
        <svg class="bus-vehicle-svg" viewBox="0 0 64 64" aria-hidden="true">
          <rect x="14" y="10" width="36" height="34" rx="8"></rect>
          <rect x="20" y="16" width="24" height="10" rx="2" class="bus-window"></rect>
          <rect x="20" y="29" width="10" height="11" rx="2" class="bus-window"></rect>
          <rect x="34" y="29" width="10" height="11" rx="2" class="bus-window"></rect>
          <circle cx="22" cy="48" r="4" class="bus-wheel"></circle>
          <circle cx="42" cy="48" r="4" class="bus-wheel"></circle>
        </svg>
      </div>
      <span class="bus-vehicle-line">${lineName}</span>
      </div>
    `,
    iconSize: [44, 44],
    iconAnchor: [22, 22]
  })
}

function getStopLookup() {
  const lookup = new Map()
  ;(currentLineMap.value?.stops || []).forEach((stop) => {
    lookup.set(stop.id, stop)
  })
  return lookup
}

function getRoutePoints() {
  return (currentLineMap.value?.routePath || []).map((point) => ({
    lat: point.lat,
    lng: point.lng
  }))
}

function distanceSquared(aLat, aLng, bLat, bLng) {
  const latDiff = aLat - bLat
  const lngDiff = aLng - bLng
  return latDiff * latDiff + lngDiff * lngDiff
}

function findNearestRouteIndex(lat, lng, routePoints) {
  if (!routePoints.length) {
    return -1
  }

  let bestIndex = 0
  let bestDistance = Number.POSITIVE_INFINITY
  routePoints.forEach((point, index) => {
    const distance = distanceSquared(lat, lng, point.lat, point.lng)
    if (distance < bestDistance) {
      bestDistance = distance
      bestIndex = index
    }
  })

  return bestIndex
}

function getRouteProgress(lat, lng, routePoints) {
  if (!routePoints.length) {
    return 0
  }
  const index = findNearestRouteIndex(lat, lng, routePoints)
  return routePoints.length > 1 ? index / (routePoints.length - 1) : 0
}

function segmentDistanceMeters(start, end) {
  return L.CRS.Earth.distance(L.latLng(start.lat, start.lng), L.latLng(end.lat, end.lng))
}

function routeDistanceMeters(routePoints, startIndex, endIndex) {
  if (!routePoints.length || endIndex <= startIndex) {
    return 0
  }

  let total = 0
  for (let index = startIndex + 1; index <= endIndex; index += 1) {
    total += segmentDistanceMeters(routePoints[index - 1], routePoints[index])
  }
  return total
}

function buildTravelPath(startLat, startLng, targetLat, targetLng, routePoints) {
  if (!routePoints.length) {
    return [
      { lat: startLat, lng: startLng },
      { lat: targetLat, lng: targetLng }
    ]
  }

  const targetIndex = findNearestRouteIndex(targetLat, targetLng, routePoints)
  if (targetIndex < 0) {
    return [
      { lat: startLat, lng: startLng },
      { lat: targetLat, lng: targetLng }
    ]
  }

  let startIndex = targetIndex
  let bestDistance = Number.POSITIVE_INFINITY
  for (let index = targetIndex; index >= 0; index -= 1) {
    const point = routePoints[index]
    const distance = distanceSquared(startLat, startLng, point.lat, point.lng)
    if (distance < bestDistance) {
      bestDistance = distance
      startIndex = index
    }
  }

  if (targetIndex <= startIndex) {
    return [
      { lat: startLat, lng: startLng },
      { lat: targetLat, lng: targetLng }
    ]
  }

  const segment = routePoints.slice(startIndex, targetIndex + 1)
  return [
    { lat: startLat, lng: startLng },
    ...segment,
    { lat: targetLat, lng: targetLng }
  ]
}

function computePathSample(path, progress) {
  if (!path || path.length < 2) {
    return {
      lat: path?.[0]?.lat ?? 0,
      lng: path?.[0]?.lng ?? 0,
      angle: 0
    }
  }

  const clamped = Math.max(0, Math.min(1, progress))
  const distances = [0]
  let total = 0

  for (let index = 1; index < path.length; index += 1) {
    const prev = path[index - 1]
    const curr = path[index]
    const segmentLength = Math.sqrt(distanceSquared(prev.lat, prev.lng, curr.lat, curr.lng))
    total += segmentLength
    distances.push(total)
  }

  if (total === 0) {
    return {
      lat: path[0].lat,
      lng: path[0].lng,
      angle: 0
    }
  }

  const target = total * clamped
  for (let index = 1; index < path.length; index += 1) {
    const prevDistance = distances[index - 1]
    const currDistance = distances[index]
    if (target <= currDistance) {
      const prev = path[index - 1]
      const curr = path[index]
      const localProgress = (target - prevDistance) / Math.max(currDistance - prevDistance, 0.000001)
      const lat = prev.lat + (curr.lat - prev.lat) * localProgress
      const lng = prev.lng + (curr.lng - prev.lng) * localProgress
      const angle = Math.atan2(curr.lng - prev.lng, curr.lat - prev.lat) * 180 / Math.PI

      return { lat, lng, angle }
    }
  }

  const prev = path[path.length - 2]
  const curr = path[path.length - 1]
  return {
    lat: curr.lat,
    lng: curr.lng,
    angle: Math.atan2(curr.lng - prev.lng, curr.lat - prev.lat) * 180 / Math.PI
  }
}

function updateMarkerRotation(marker, angle) {
  const element = marker.getElement()
  if (!element) {
    return
  }

  const rotator = element.querySelector('.bus-vehicle-rotator')
  if (rotator) {
    rotator.style.transform = `rotate(${angle}deg)`
  }
}

function resolveRemainingSeconds(vehicle, firstLeg) {
  const candidates = [
    vehicle?.timeToStationSeconds,
    vehicle?.timeToNextStopSeconds,
    firstLeg?.durationSeconds,
    firstLeg?.durationMs ? Math.round(firstLeg.durationMs / 1000) : null
  ]

  for (const candidate of candidates) {
    const value = Number(candidate)
    if (Number.isFinite(value) && value > 0) {
      return value
    }
  }

  return 60
}

function selectVisibleVehicles(vehicles = []) {
  return vehicles.filter((vehicle) => vehicle?.vehicleId)
}

function calculateVirtualStartPoint(routePoints, leg, remainingSeconds) {
  if (!leg?.from || !leg?.to || !routePoints.length) {
    const fallback = leg?.from || leg?.to || { lat: 51.5074, lng: -0.1278 }
    return {
      lat: fallback.lat,
      lng: fallback.lng,
      holdMs: 0
    }
  }

  const toIndex = findNearestRouteIndex(leg.to.lat, leg.to.lng, routePoints)
  if (toIndex < 0) {
    return {
      lat: leg.from.lat,
      lng: leg.from.lng,
      holdMs: 0
    }
  }

  let fromIndex = toIndex
  let bestDistance = Number.POSITIVE_INFINITY
  for (let index = toIndex; index >= 0; index -= 1) {
    const point = routePoints[index]
    const distance = distanceSquared(leg.from.lat, leg.from.lng, point.lat, point.lng)
    if (distance < bestDistance) {
      bestDistance = distance
      fromIndex = index
    }
  }

  if (toIndex <= fromIndex) {
    return {
      lat: leg.from.lat,
      lng: leg.from.lng,
      holdMs: 0
    }
  }

  const segmentDistance = routeDistanceMeters(routePoints, fromIndex, toIndex)
  const estimatedSpeed = 5
  const normalizedRemainingSeconds = Math.max(0, Number(remainingSeconds) || 0)
  let remainingDistance = estimatedSpeed * normalizedRemainingSeconds

  if (remainingDistance >= segmentDistance) {
    const holdSeconds = (remainingDistance - segmentDistance) / estimatedSpeed
    const routeStart = routePoints[0] || leg.from
    return {
      lat: routeStart.lat,
      lng: routeStart.lng,
      holdMs: Math.max(0, holdSeconds * 1000)
    }
  }

  for (let index = toIndex; index > fromIndex; index -= 1) {
    const current = routePoints[index]
    const previous = routePoints[index - 1]
    const step = segmentDistanceMeters(previous, current)
    if (remainingDistance <= step) {
      const ratio = step <= 0 ? 0 : remainingDistance / step
      return {
        lat: current.lat + (previous.lat - current.lat) * ratio,
        lng: current.lng + (previous.lng - current.lng) * ratio,
        holdMs: 0
      }
    }
    remainingDistance -= step
  }

  const routeStart = routePoints[Math.max(0, fromIndex)] || leg.from
  return {
    lat: routeStart.lat,
    lng: routeStart.lng,
    holdMs: 0
  }
}

function mapVehiclePlan(vehicle, stopLookup) {
  return (vehicle.travelPlan || [])
    .map((leg) => {
      const from = stopLookup.get(leg.fromStopId)
      const to = stopLookup.get(leg.toStopId)
      if (!from || !to) {
        return null
      }
      const durationSeconds = resolveRemainingSeconds({}, leg)
      return {
        from,
        to,
        fromStopId: leg.fromStopId,
        toStopId: leg.toStopId,
        durationSeconds,
        durationMs: durationSeconds * 1000
      }
    })
    .filter(Boolean)
}

function animateVehicles(timestamp) {
  let hasActiveAnimation = false

  vehicleMarkers.forEach((entry) => {
    if (!entry.marker) {
      return
    }

    if (!entry.plan.length) {
      if (entry.waitingPoint) {
        entry.marker.setLatLng([entry.waitingPoint.lat, entry.waitingPoint.lng])
      }
      return
    }

    if (entry.waitUntil && timestamp < entry.waitUntil) {
      entry.marker.setLatLng([entry.waitingPoint.lat, entry.waitingPoint.lng])
      hasActiveAnimation = true
      return
    }

    if (entry.waitUntil && timestamp >= entry.waitUntil) {
      entry.waitUntil = null
      if (entry.currentLegIndex < entry.plan.length) {
        const nextLeg = entry.plan[entry.currentLegIndex]
        entry.path = buildTravelPath(
          entry.waitingPoint.lat,
          entry.waitingPoint.lng,
          nextLeg.to.lat,
          nextLeg.to.lng,
          getRoutePoints()
        )
        entry.segmentStartTime = timestamp
        entry.segmentEndTime = timestamp + nextLeg.durationMs
      }
    }

    if (entry.currentLegIndex >= entry.plan.length) {
      if (entry.waitingPoint) {
        entry.marker.setLatLng([entry.waitingPoint.lat, entry.waitingPoint.lng])
      }
      return
    }

    if (timestamp < entry.segmentStartTime) {
      if (entry.waitingPoint) {
        entry.marker.setLatLng([entry.waitingPoint.lat, entry.waitingPoint.lng])
      }
      hasActiveAnimation = true
      return
    }

    const duration = Math.max(1, entry.segmentEndTime - entry.segmentStartTime)
    const progress = Math.min(1, Math.max(0, (timestamp - entry.segmentStartTime) / duration))
    const sample = computePathSample(entry.path, progress)
    entry.marker.setLatLng([sample.lat, sample.lng])
    updateMarkerRotation(entry.marker, sample.angle)

    if (progress >= 1) {
      const completedLeg = entry.plan[entry.currentLegIndex]
      entry.marker.setLatLng([completedLeg.to.lat, completedLeg.to.lng])
      entry.waitingPoint = completedLeg.to
      const nextLeg = entry.plan[entry.currentLegIndex + 1]
      if (nextLeg) {
        entry.currentLegIndex += 1
        entry.waitUntil = timestamp + STATION_HOLD_MS
      } else {
        entry.segmentStartTime = timestamp
        entry.segmentEndTime = timestamp
        entry.waitUntil = null
      }
      hasActiveAnimation = true
      return
    }

    if (progress < 1 || entry.currentLegIndex < entry.plan.length) {
      hasActiveAnimation = true
    }
  })

  if (hasActiveAnimation) {
    vehicleAnimationFrame = requestAnimationFrame(animateVehicles)
  } else {
    vehicleAnimationFrame = null
  }
}

function startVehicleAnimationLoop() {
  if (vehicleAnimationFrame) {
    cancelAnimationFrame(vehicleAnimationFrame)
  }
  vehicleAnimationFrame = requestAnimationFrame(animateVehicles)
}

function updateVehicleMarkers(vehicles = []) {
  if (!leafletMap.value) {
    return
  }

  if (!vehicleLayerGroup.value) {
    vehicleLayerGroup.value = L.layerGroup().addTo(leafletMap.value)
  }

  const stopLookup = getStopLookup()
  const routePoints = getRoutePoints()
  const visibleVehicles = selectVisibleVehicles(vehicles)
  const nextIds = new Set(visibleVehicles.map((vehicle) => vehicle.vehicleId))

  vehicleMarkers.forEach((entry, vehicleId) => {
    if (!nextIds.has(vehicleId)) {
      vehicleLayerGroup.value.removeLayer(entry.marker)
      vehicleMarkers.delete(vehicleId)
    }
  })

  const now = performance.now()
  visibleVehicles.forEach((vehicle) => {
    const markerLabel = selectedLine.value?.name || '52'
    const directionText = vehicle.destinationName || '终点站'
    const nextStop = stopLookup.get(vehicle.nextStopId)
    const plan = mapVehiclePlan(vehicle, stopLookup)
    const remainingSeconds = Number(vehicle.timeToStationSeconds ?? vehicle.timeToNextStopSeconds ?? 60)
    const currentDurationMs = Math.max(1000, remainingSeconds * 1000)
    const popupText = `
      <div class="stop-popup">
        <strong>${markerLabel}路公交</strong>
        <div>开往 ${directionText}</div>
        <div>${vehicle.currentLocation || '车辆行驶中'}</div>
        <div>下一站：${vehicle.nextStopName || '未知'}</div>
        <div>${formatArrival({ timeToStationSeconds: vehicle.timeToNextStopSeconds })}</div>
      </div>
    `

    const existing = vehicleMarkers.get(vehicle.vehicleId)
    if (!existing) {
      const firstLeg = plan[0]
      const virtualStart = calculateVirtualStartPoint(
        routePoints,
        firstLeg || {
          from: nextStop,
          to: nextStop,
          durationSeconds: remainingSeconds
        },
        remainingSeconds
      )
      const path = buildTravelPath(
        virtualStart.lat,
        virtualStart.lng,
        firstLeg?.to?.lat ?? nextStop?.lat ?? virtualStart.lat,
        firstLeg?.to?.lng ?? nextStop?.lng ?? virtualStart.lng,
        routePoints
      )
      const marker = L.marker([virtualStart.lat, virtualStart.lng], {
        icon: createDirectionalVehicleIcon(markerLabel, 0),
        zIndexOffset: 1000
      }).bindPopup(popupText)

      vehicleLayerGroup.value.addLayer(marker)
      const initialSample = computePathSample(path, 0)
      updateMarkerRotation(marker, initialSample.angle)
      vehicleMarkers.set(vehicle.vehicleId, {
        marker,
        plan,
        currentLegIndex: 0,
        path,
        segmentStartTime: now + virtualStart.holdMs,
        segmentEndTime: now + currentDurationMs,
        waitUntil: null,
        waitingPoint: { lat: virtualStart.lat, lng: virtualStart.lng }
      })
      return
    }

    existing.marker.setIcon(createDirectionalVehicleIcon(markerLabel, 0))
    existing.marker.setPopupContent(popupText)
    const currentLeg = existing.plan[existing.currentLegIndex]
    const newRemainingMs = currentDurationMs

    if (!currentLeg && plan.length > 0) {
      existing.plan = plan
      existing.currentLegIndex = 0
      existing.path = buildTravelPath(
        existing.waitingPoint?.lat ?? plan[0].from.lat,
        existing.waitingPoint?.lng ?? plan[0].from.lng,
        plan[0].to.lat,
        plan[0].to.lng,
        routePoints
      )
      existing.segmentStartTime = now
      existing.segmentEndTime = now + newRemainingMs
      existing.waitUntil = null
      existing.waitingPoint = plan[0].from
      return
    }

    if (currentLeg && plan.length > 0 && currentLeg.toStopId === plan[0].toStopId && existing.currentLegIndex === 0) {
      const currentDuration = Math.max(1, existing.segmentEndTime - existing.segmentStartTime)
      const currentProgress = Math.max(0, (now - existing.segmentStartTime) / currentDuration)
      const remainingProgress = 1 - currentProgress

      existing.plan = [
        {
          ...currentLeg,
          ...plan[0],
          durationMs: newRemainingMs
        },
        ...plan.slice(1)
      ]

      if (currentProgress < 1 && remainingProgress > 0) {
        const newTotalDuration = newRemainingMs / remainingProgress
        existing.segmentEndTime = now + newRemainingMs
        existing.segmentStartTime = existing.segmentEndTime - newTotalDuration
      } else if (currentProgress >= 1) {
        existing.segmentStartTime = now
        existing.segmentEndTime = now + newRemainingMs
      }
      return
    }

    if (currentLeg && plan.length > 0 && currentLeg.toStopId === plan[0].toStopId) {
      existing.plan = [
        {
          ...existing.plan[existing.currentLegIndex],
          ...plan[0],
          durationMs: newRemainingMs
        },
        ...plan.slice(1)
      ]
      existing.segmentEndTime = now + newRemainingMs
      return
    }

    if (plan.length > 0 && (!existing.waitUntil || now >= existing.waitUntil)) {
      existing.plan = plan
      existing.currentLegIndex = 0
      existing.path = buildTravelPath(
        existing.waitingPoint?.lat ?? plan[0].from.lat,
        existing.waitingPoint?.lng ?? plan[0].from.lng,
        plan[0].to.lat,
        plan[0].to.lng,
        routePoints
      )
      existing.segmentStartTime = now
      existing.segmentEndTime = now + newRemainingMs
      existing.waitUntil = null
      existing.waitingPoint = plan[0].from
      return
    }
  })

  startVehicleAnimationLoop()
}

function stopVehicleRefresh() {
  if (vehicleRefreshTimer) {
    clearInterval(vehicleRefreshTimer)
    vehicleRefreshTimer = null
  }
  if (vehicleAnimationFrame) {
    cancelAnimationFrame(vehicleAnimationFrame)
    vehicleAnimationFrame = null
  }
}

function startVehicleRefresh() {
  stopVehicleRefresh()
  vehicleRefreshTimer = setInterval(() => {
    loadSelectedLineMap(true)
  }, VEHICLE_REFRESH_MS)
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

async function selectLine(line) {
  selectedLine.value = line
  searchQuery.value = line.displayName
  isSuggestionOpen.value = false

  if (currentCity.value !== '伦敦') {
    currentCity.value = '伦敦'
    return
  }

  await ensureLondonMap()
  await loadSelectedLineMap(false)
  startVehicleRefresh()
}

async function loadSelectedLineMap(refreshOnly = false) {
  if (!selectedLine.value?.id) {
    return
  }

  try {
    const response = await fetch(`/api/lines/${selectedLine.value.id}/map`)
    if (!response.ok) {
      return
    }
    const lineMap = await response.json()
    currentLineMap.value = lineMap
    vehicleStatus.value = lineMap.vehicleStatus || ''
    vehicleMessage.value = lineMap.vehicleMessage || ''
    if (!refreshOnly) {
      renderLineOnMap(lineMap, true)
    }
    updateVehicleMarkers(Array.isArray(lineMap.vehicles) ? lineMap.vehicles : [])
  } catch {
    if (!refreshOnly) {
      clearRouteLayer()
    }
    clearVehicleLayer()
    vehicleStatus.value = 'error'
    vehicleMessage.value = '请求车辆位置失败，请检查后端或 TfL 接口连通性'
  }
}

watch(
  currentCity,
  async (city) => {
    if (city === '伦敦') {
      await ensureLondonMap()
      if (selectedLine.value?.id) {
        await loadSelectedLineMap(false)
        startVehicleRefresh()
      }
      return
    }

    stopVehicleRefresh()
    clearVehicleLayer()
    if (leafletMap.value) {
      leafletMap.value.invalidateSize()
    }
  },
  { immediate: true }
)

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
  stopVehicleRefresh()
  clearVehicleLayer()
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

      <section class="route-planner">
        <div class="route-inputs">
          <label class="route-field">
            <span class="sr-only">起点</span>
            <input v-model="routeStart" type="text" placeholder="起点" />
          </label>

          <label class="route-field">
            <span class="sr-only">终点</span>
            <input v-model="routeEnd" type="text" placeholder="终点" />
          </label>

          <button
            class="swap-button"
            type="button"
            aria-label="互换起点和终点"
            @click="swapRoutePoints"
          >
            <span class="swap-icon"></span>
          </button>
        </div>

        <div class="route-actions">
          <button class="route-time" type="button">现在出发</button>
          <button class="route-go" type="button">查询</button>
        </div>
      </section>
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

        <button class="primary-button" type="button">
          <span class="icon-bell small"></span>
          设置提醒
        </button>
      </div>

      <div
        v-if="selectedLine?.id === '52' && currentCity === '伦敦'"
        class="vehicle-status-card"
        :class="vehicleStatus"
      >
        <strong>52路车辆状态</strong>
        <span>{{ vehicleMessage }}</span>
      </div>
    </main>
  </div>
</template>
