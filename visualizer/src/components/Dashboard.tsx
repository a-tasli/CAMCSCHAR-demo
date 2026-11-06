"use client";

import React, { useState, useMemo, useEffect } from 'react';
import dynamic from 'next/dynamic';
import SensorCharts from './SensorCharts';

export interface ExperimentDataRecord {
  timestamp: string;
  timeLabel?: string;
  timeMs?: number;
  hr: number | null;
  hrv_rmssd: number | null;
  step_variance: number | null;
  is_speaking: boolean;
  lat: number | null;
  lon: number | null;
  poi: string;
  load_level: string;
  combined_load_index: number | null;
  physical_strain_score?: number | null;
  mental_hr_bpm?: number | null;
  is_auto_mode?: boolean;
  is_detailed_script?: boolean;
  is_large_image_mode?: boolean;
  script_line?: number;
  tts_speed?: number;
  inter_line_delay_ms?: number;
  is_checkpoint_active?: boolean;
  presentation_mode?: string;
  trigger?: string;
  [key: string]: unknown;
}

export interface SurveyData {
  metadata?: {
    participant_id?: string;
    condition?: string;
    date_time?: string;
    phase_order?: string;
  };
  scores?: {
    overall_retention_score?: number;
    core_retention_score?: number;
    detailed_retention_score?: number;
    attention_score?: number;
    attention_status?: string;
    omission_rate_percent?: number;
    error_rate_percent?: number;
  };
  nasa_tlx?: {
    mean_workload_score?: number;
    mental_demand?: number;
    physical_demand?: number;
    temporal_demand?: number;
    performance?: number;
    effort?: number;
    frustration?: number;
  };
}

// Dynamically import Map to prevent SSR errors
const Map = dynamic(() => import('./Map'), {
  ssr: false,
  loading: () => <div style={{height:'100%',display:'flex',alignItems:'center',justifyContent:'center',color:'#64748b'}}>Loading Map...</div>
});

function extractExperimentData(json: unknown): ExperimentDataRecord[] {
  if (Array.isArray(json)) return json as ExperimentDataRecord[];
  if (json && typeof json === 'object') {
    const obj = json as Record<string, unknown>;
    const raw = obj.experiment_data ?? obj.sensor_data ?? obj.data;
    if (Array.isArray(raw)) return raw as ExperimentDataRecord[];
  }
  return [];
}

function processRecords(rawItems: ExperimentDataRecord[]): ExperimentDataRecord[] {
  return rawItems.map((item) => {
    const d = new Date(item.timestamp);
    return {
      ...item,
      timeLabel: d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
      timeMs: d.getTime()
    };
  });
}

export default function Dashboard() {
  const [data, setData] = useState<ExperimentDataRecord[]>([]);
  const [fileName, setFileName] = useState<string | null>(null);
  const [visibleCount, setVisibleCount] = useState(100);
  const [surveyData, setSurveyData] = useState<SurveyData | null>(null);

  useEffect(() => {
    fetch('/api/latest')
      .then(res => {
        if (!res.ok) throw new Error('No data');
        return res.json();
      })
      .then(json => {
        const rawItems = extractExperimentData(json);
        if (rawItems.length > 0) {
          setData(processRecords(rawItems));
          setVisibleCount(100);
          setFileName('Latest uploaded from App');
        }
        if (json && typeof json === 'object' && 'survey' in json) {
          setSurveyData((json as { survey: SurveyData }).survey);
        }
      })
      .catch(() => {
        // No latest data available yet
      });
  }, []);

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
        const rawItems = extractExperimentData(json);
        if (rawItems.length > 0) {
          setData(processRecords(rawItems));
          setVisibleCount(100);
        }
        if (json && typeof json === 'object' && 'survey' in json) {
          setSurveyData((json as { survey: SurveyData }).survey);
        } else {
          setSurveyData(null);
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
      const isSpeakEvent = Boolean(e.is_speaking);
      const isPoi = e.poi !== 'None';
      const desc: string[] = [];

      if (isSpeakEvent) desc.push("User spoke");
      if (e.is_auto_mode) desc.push(`Auto Mode`);
      if (e.combined_load_index != null) {
        desc.push(`Load: ${e.load_level} (${e.combined_load_index.toFixed(2)})`);
      } else {
        desc.push(`Load: ${e.load_level}`);
      }
      desc.push(`POI: ${e.poi}`);

      return {
        id: idx,
        time: e.timeLabel ?? '',
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
          {fileName && (
            <p style={{ fontSize: '0.85rem', color: 'var(--muted)', marginTop: '0.25rem' }}>
              Source: <strong>{fileName}</strong>
            </p>
          )}
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
          {surveyData && (
            <div className="card" style={{ borderLeft: '4px solid #10b981' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                <h2 className="card-title" style={{ margin: 0 }}>Post-Tour Retention & Workload Survey Results</h2>
                <span className="badge badge-low" style={{ fontSize: '0.875rem' }}>
                  {surveyData.metadata?.condition || 'Experiment'}
                </span>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem' }}>
                <div style={{ padding: '0.75rem', background: 'var(--muted-bg)', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--muted)', fontWeight: 600 }}>PARTICIPANT</div>
                  <div style={{ fontSize: '1.25rem', fontWeight: 700 }}>{surveyData.metadata?.participant_id || 'N/A'}</div>
                </div>
                <div style={{ padding: '0.75rem', background: 'var(--muted-bg)', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--muted)', fontWeight: 600 }}>OVERALL RETENTION</div>
                  <div style={{ fontSize: '1.25rem', fontWeight: 700, color: '#10b981' }}>
                    {surveyData.scores?.overall_retention_score ?? '-'} / 18
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--muted)' }}>
                    Core: {surveyData.scores?.core_retention_score ?? '-'}/12 | Detailed: {surveyData.scores?.detailed_retention_score ?? '-'}/6
                  </div>
                </div>
                <div style={{ padding: '0.75rem', background: 'var(--muted-bg)', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--muted)', fontWeight: 600 }}>NASA-TLX WORKLOAD</div>
                  <div style={{ fontSize: '1.25rem', fontWeight: 700, color: '#8b5cf6' }}>
                    {surveyData.nasa_tlx?.mean_workload_score != null ? surveyData.nasa_tlx.mean_workload_score.toFixed(1) : '-'} / 100
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--muted)' }}>
                    Mental: {surveyData.nasa_tlx?.mental_demand ?? '-'} | Temporal: {surveyData.nasa_tlx?.temporal_demand ?? '-'}
                  </div>
                </div>
                <div style={{ padding: '0.75rem', background: 'var(--muted-bg)', borderRadius: '6px' }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--muted)', fontWeight: 600 }}>ATTENTION CHECK</div>
                  <div style={{ fontSize: '1.25rem', fontWeight: 700, color: surveyData.scores?.attention_score === 2 ? '#10b981' : '#f59e0b' }}>
                    {surveyData.scores?.attention_status || '-'}
                  </div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--muted)' }}>
                    Omission: {surveyData.scores?.omission_rate_percent != null ? surveyData.scores.omission_rate_percent.toFixed(1) : '-'}%
                  </div>
                </div>
              </div>
            </div>
          )}

          <div className="card">
            <h2 className="card-title">Physiological & Gait Data</h2>
            <SensorCharts data={data} />
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
                  <th>Load Index</th>
                  <th>Script Line</th>
                  <th>POI</th>
                  <th>Trigger</th>
                </tr>
              </thead>
              <tbody>
                {data.slice(0, visibleCount).map((d, i) => (
                  <tr key={i}>
                    <td>{d.timeLabel}</td>
                    <td>{d.hr ?? '-'}</td>
                    <td>{d.hrv_rmssd != null ? d.hrv_rmssd.toFixed(2) : '-'}</td>
                    <td>{d.step_variance != null ? d.step_variance.toFixed(2) : '-'}</td>
                    <td><span className={`badge ${getBadgeClass(d.load_level)}`}>{d.load_level}</span></td>
                    <td>{d.combined_load_index != null ? d.combined_load_index.toFixed(2) : '-'}</td>
                    <td>{d.script_line ?? '-'}</td>
                    <td>{d.poi.replace(/_/g, ' ')}</td>
                    <td>{d.trigger ?? 'periodic'}</td>
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
