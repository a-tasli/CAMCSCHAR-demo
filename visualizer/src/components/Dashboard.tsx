"use client";

import React, { useState, useMemo } from 'react';
import dynamic from 'next/dynamic';
import { 
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
  ReferenceArea
} from 'recharts';

// Dynamically import Map to prevent SSR errors
const Map = dynamic(() => import('./Map'), { ssr: false, loading: () => <div style={{height:'100%',display:'flex',alignItems:'center',justifyContent:'center',color:'#64748b'}}>Loading Map...</div> });

export default function Dashboard() {
  const [data, setData] = useState<any[]>([]);
  const [fileName, setFileName] = useState<string | null>(null);
  const [visibleCount, setVisibleCount] = useState(100);

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const bottom = e.currentTarget.scrollHeight - e.currentTarget.scrollTop <= e.currentTarget.clientHeight + 50;
    if (bottom && visibleCount < data.length) {
      setVisibleCount(prev => Math.min(prev + 100, data.length));
    }
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setFileName(file.name);
    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const json = JSON.parse(event.target?.result as string);
        if (Array.isArray(json)) {
          // Process timestamps for chart rendering
          const processed = json.map(item => {
            const d = new Date(item.timestamp);
            return {
              ...item,
              timeLabel: d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
              timeMs: d.getTime()
            };
          });
          setData(processed);
          setVisibleCount(100);
        }
      } catch (err) {
        alert("Failed to parse JSON file");
        console.error(err);
      }
    };
    reader.readAsText(file);
  };

  const getBadgeClass = (level: string) => {
    switch (level) {
      case 'Low': return 'badge-low';
      case 'Medium': return 'badge-medium';
      case 'High': return 'badge-high';
      case 'Overload': return 'badge-overload';
      default: return '';
    }
  };

  const events = useMemo(() => {
    return data.filter(d => d.trigger === 'event').map((e, idx) => {
      let title = "State Changed";
      let desc = [];
      let isSpeakEvent = e.is_speaking;
      let isPoi = e.poi !== 'None';
      
      if (isSpeakEvent) desc.push("User spoke");
      if (e.is_auto_mode) desc.push(`Auto Mode`);
      desc.push(`Load: ${e.load_level}`);
      desc.push(`POI: ${e.poi}`);

      return {
        id: idx,
        time: e.timeLabel,
        title: isPoi ? `POI: ${e.poi.replace(/_/g, ' ')}` : 'Update',
        desc: desc.join(' | '),
        type: isSpeakEvent ? 'event-speak' : (isPoi ? 'event-poi' : '')
      };
    }).reverse();
  }, [data]);

  return (
    <div className="container">
      <header className="header">
        <div>
          <h1>CAMCSCHAR Data Visualizer</h1>
          <p>Academic dashboard for analyzing multimodal cognitive load experiments</p>
        </div>
        <div>
          <label className="btn btn-primary">
            Upload JSON Data
            <input type="file" accept=".json" onChange={handleFileUpload} style={{display:'none'}} />
          </label>
        </div>
      </header>

      {data.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '4rem 0', color: 'var(--muted)' }}>
          <p>Please upload an experiment JSON file to view the analysis.</p>
        </div>
      ) : (
        <div className="grid">
          <div className="card">
            <h2 className="card-title">Physiological & Gait Data</h2>
            <div className="chart-container">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={data} margin={{ top: 5, right: 30, left: 20, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                  <XAxis dataKey="timeLabel" minTickGap={30} tick={{fontSize: 12, fill: '#64748b'}} />
                  <YAxis yAxisId="left" tick={{fontSize: 12, fill: '#64748b'}} />
                  <YAxis yAxisId="right" orientation="right" tick={{fontSize: 12, fill: '#64748b'}} />
                  <Tooltip 
                    contentStyle={{ borderRadius: '8px', border: '1px solid #e2e8f0', boxShadow: '0 4px 6px rgba(0,0,0,0.05)' }} 
                  />
                  <Legend wrapperStyle={{ fontSize: '14px', paddingTop: '10px' }} />
                  
                  {/* Colored regions based on load level could be added here using ReferenceArea if we tracked segments */}
                  
                  <Line yAxisId="left" type="monotone" dataKey="hr" name="Heart Rate (BPM)" stroke="#ef4444" strokeWidth={2} dot={false} />
                  <Line yAxisId="left" type="monotone" dataKey="hrv_rmssd" name="HRV (RMSSD)" stroke="#3b82f6" strokeWidth={2} dot={false} />
                  <Line yAxisId="right" type="monotone" dataKey="step_variance" name="Step Variance" stroke="#10b981" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </div>

          <div className="grid grid-2">
            <div className="card">
              <h2 className="card-title">GPS Path & Load Map</h2>
              <div style={{ height: '350px' }}>
                <Map data={data} />
              </div>
            </div>

            <div className="card">
              <h2 className="card-title">Event Timeline</h2>
              <div className="timeline">
                {events.length === 0 ? <p style={{color: 'var(--muted)'}}>No events recorded.</p> : null}
                {events.map(ev => (
                  <div key={ev.id} className={`timeline-event ${ev.type}`}>
                    <div className="timeline-time">{ev.time}</div>
                    <div className="timeline-content">
                      <div className="timeline-title">{ev.title}</div>
                      <div className="timeline-desc">{ev.desc}</div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <div className="card" style={{overflowX: 'auto', overflowY: 'auto', maxHeight: '500px'}} onScroll={handleScroll}>
            <h2 className="card-title">Raw Data Log</h2>
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>HR</th>
                  <th>HRV</th>
                  <th>Gait Var</th>
                  <th>Load Level</th>
                  <th>Script Line</th>
                  <th>POI</th>
                  <th>Trigger</th>
                </tr>
              </thead>
              <tbody>
                {data.slice(0, visibleCount).map((d, i) => (
                  <tr key={i}>
                    <td>{d.timeLabel}</td>
                    <td>{d.hr || '-'}</td>
                    <td>{d.hrv_rmssd ? d.hrv_rmssd.toFixed(2) : '-'}</td>
                    <td>{d.step_variance ? d.step_variance.toFixed(2) : '-'}</td>
                    <td><span className={`badge ${getBadgeClass(d.load_level)}`}>{d.load_level}</span></td>
                    <td>{d.script_line || '-'}</td>
                    <td>{d.poi.replace(/_/g, ' ')}</td>
                    <td>{d.trigger}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {visibleCount < data.length && <p style={{textAlign: 'center', margin: '1rem', color: 'var(--muted)', fontSize: '0.875rem'}}>Scroll down to load more...</p>}
          </div>
        </div>
      )}
    </div>
  );
}
