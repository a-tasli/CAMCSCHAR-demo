"use client";

import { useEffect } from 'react';
import { MapContainer, TileLayer, Marker, Popup, Polyline } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import L from 'leaflet';

// Fix Leaflet icon issue in Next.js
const DefaultIcon = L.icon({
  iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
  shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
  iconSize: [25, 41],
  iconAnchor: [12, 41],
});

L.Marker.prototype.options.icon = DefaultIcon;

interface MapProps {
  data: any[];
}

export default function Map({ data }: MapProps) {
  const validLocations = data.filter(d => d.lat !== null && d.lon !== null);
  
  if (validLocations.length === 0) {
    return <div style={{ height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#64748b' }}>No GPS data available</div>;
  }

  const positions: [number, number][] = validLocations.map(d => [d.lat, d.lon]);
  const center = positions[0];

  const getLoadColor = (level: string) => {
    switch (level) {
      case 'Low': return '#10b981';
      case 'Medium': return '#f59e0b';
      case 'High': return '#ef4444';
      case 'Overload': return '#991b1b';
      default: return '#3b82f6';
    }
  };

  return (
    <MapContainer center={center} zoom={18} scrollWheelZoom={true} style={{ height: '100%', width: '100%', borderRadius: '0.5rem' }}>
      <TileLayer
        attribution='&copy; OpenStreetMap contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      
      <Polyline positions={positions} color="#2563eb" weight={3} opacity={0.7} />

      {validLocations.map((d, idx) => (
        // Only show markers for events or every 10th point to avoid clutter
        (d.trigger === 'event' || idx === 0 || idx === validLocations.length - 1) && (
          <Marker key={idx} position={[d.lat, d.lon]}>
            <Popup>
              <strong>{new Date(d.timestamp).toLocaleTimeString()}</strong><br/>
              POI: {d.poi}<br/>
              Load: {d.load_level} {d.combined_load_index ? `(${d.combined_load_index.toFixed(2)})` : ''}<br/>
              Trigger: {d.trigger}
            </Popup>
          </Marker>
        )
      ))}
    </MapContainer>
  );
}
