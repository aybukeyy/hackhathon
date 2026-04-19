import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

// Fix default marker icons
delete L.Icon.Default.prototype._getIconUrl
L.Icon.Default.mergeOptions({
  iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
  iconUrl:       'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  shadowUrl:     'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
})

export default function MapPanel({ routeData }) {
  const ref = useRef(null)
  const mapRef = useRef(null)

  useEffect(() => {
    if (!routeData || !ref.current) return
    if (mapRef.current) { mapRef.current.remove(); mapRef.current = null }

    const map = L.map(ref.current, { zoomControl: true, scrollWheelZoom: true })
    mapRef.current = map

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap'
    }).addTo(map)

    const bounds = []

    // Draw segments
    const { segments = [], markers = [] } = routeData
    segments.forEach(seg => {
      if (!seg.polyline || seg.polyline.length < 2) return
      const latlngs = seg.polyline.map(p => [p[0], p[1]])
      bounds.push(...latlngs)
      const dash = seg.type === 'walk' ? '6 8' : null
      L.polyline(latlngs, {
        color: seg.color || '#3b82f6',
        weight: seg.type === 'walk' ? 4 : 5,
        dashArray: dash,
        opacity: 0.85
      }).bindPopup(`<b>${seg.description || seg.line}</b>`).addTo(map)
    })

    // Draw markers
    markers.forEach(mk => {
      const icon = L.divIcon({
        className: '',
        html: `<div style="background:white;border:2px solid #3b82f6;border-radius:8px;padding:2px 6px;font-size:11px;font-weight:600;white-space:nowrap;box-shadow:0 2px 6px rgba(0,0,0,.2)">${mk.label}</div>`,
        iconAnchor: [0, 0]
      })
      const m = L.marker([mk.lat, mk.lon], { icon }).addTo(map)
      bounds.push([mk.lat, mk.lon])
    })

    if (bounds.length) {
      if (bounds.length === 1) {
        map.setView(bounds[0], 16)
      } else {
        map.fitBounds(bounds, { padding: [20, 20] })
      }
    }

    return () => { if (mapRef.current) { mapRef.current.remove(); mapRef.current = null } }
  }, [routeData])

  if (!routeData) return null

  return (
    <div className="mt-2 rounded-xl overflow-hidden border border-gray-200 shadow-sm">
      <div ref={ref} style={{ height: 280, width: '100%' }} />
      {routeData.segments && (
        <div className="bg-gray-50 px-3 py-2 text-xs space-y-1">
          {routeData.segments.map((s, i) => (
            <div key={i} className="flex items-center gap-2">
              <span className="w-3 h-3 rounded-full flex-shrink-0" style={{ background: s.color }} />
              <span className="text-gray-700">{s.description}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
