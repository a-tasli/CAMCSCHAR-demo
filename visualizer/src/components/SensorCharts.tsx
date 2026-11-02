"use client";

import React, { useState } from 'react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
  type TooltipContentProps
} from 'recharts';

export type SensorDataRow = {
  timeLabel?: string;
  [key: string]: unknown;
};

type SensorVariable = {
  key: string;
  name: string;
  unit: string;
  color: string;
  decimals: number;
  axis: 'left' | 'right';
};

const VARIABLES: SensorVariable[] = [
  { key: 'hr', name: 'Heart Rate', unit: 'BPM', color: '#ef4444', decimals: 0, axis: 'left' },
  { key: 'hrv_rmssd', name: 'HRV (RMSSD)', unit: 'ms', color: '#3b82f6', decimals: 1, axis: 'left' },
  { key: 'mental_hr_bpm', name: 'Mental HR (Residual)', unit: 'bpm', color: '#f97316', decimals: 1, axis: 'left' },
  { key: 'step_variance', name: 'Step Variance', unit: '', color: '#10b981', decimals: 3, axis: 'right' },
  { key: 'physical_strain_score', name: 'Physical Strain (%HRR)', unit: '', color: '#0ea5e9', decimals: 0, axis: 'right' },
  { key: 'combined_load_index', name: 'Combined Load Index', unit: '', color: '#8b5cf6', decimals: 2, axis: 'right' },
];

const SYNC_ID = 'sensor-charts';

const formatValue = (value: unknown, decimals: number) =>
  value == null || Number.isNaN(Number(value)) ? '—' : Number(value).toFixed(decimals);

type SyncTooltipProps = TooltipContentProps & {
  highlight?: string;
};

/**
 * Tooltip shared by all charts. Shows the value of every variable at the hovered
 * time, so hovering one graph also reports what the y values were on the other
 * graphs. The hovered chart's own variable is emphasized via `highlight`.
 */
function SyncTooltip({ active, payload, label, highlight }: SyncTooltipProps) {
  if (!active || !payload || payload.length === 0) return null;
  const row: Record<string, unknown> = payload[0]?.payload ?? {};
  return (
    <div className="sync-tooltip">
      <div className="sync-tooltip-time">{label}</div>
      {VARIABLES.map(v => {
        const emphasized = highlight === v.key;
        return (
          <div key={v.key} className={`sync-tooltip-row${emphasized ? ' emphasized' : ''}`}>
            <span className="legend-dot" style={{ background: v.color }} />
            <span className="sync-tooltip-name">{v.name}</span>
            <span className="sync-tooltip-value" style={emphasized ? { color: v.color } : undefined}>
              {formatValue(row[v.key], v.decimals)}{v.unit ? ` ${v.unit}` : ''}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/**
 * Stacked per-variable charts. All charts share the same data and x-axis, and are
 * synchronized via syncId so hovering one shows a cursor and tooltip on all of
 * them at the same x position.
 */
function StackedCharts({ data }: { data: SensorDataRow[] }) {
  return (
    <div className="mini-chart-stack">
      {VARIABLES.map((v, i) => {
        const isBottom = i === VARIABLES.length - 1;
        return (
          <div key={v.key} className="mini-chart">
            <div className="mini-chart-heading">
              <span className="legend-dot" style={{ background: v.color }} />
              <span>{v.name}</span>
              {v.unit ? <span className="mini-chart-unit">({v.unit})</span> : null}
            </div>
            <ResponsiveContainer width="100%" height={130}>
              <LineChart
                data={data}
                syncId={SYNC_ID}
                syncMethod="index"
                margin={{ top: 5, right: 30, left: 12, bottom: isBottom ? 5 : 0 }}
              >
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
                {isBottom ? (
                  <XAxis dataKey="timeLabel" minTickGap={40} tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} />
                ) : (
                  <XAxis dataKey="timeLabel" hide />
                )}
                <YAxis width={52} tick={{ fontSize: 11, fill: '#64748b' }} tickLine={false} domain={['auto', 'auto']} />
                <Tooltip
                  content={(props) => <SyncTooltip {...props} highlight={v.key} />}
                  cursor={{ stroke: '#94a3b8', strokeDasharray: '4 4' }}
                />
                <Line
                  type="monotone"
                  dataKey={v.key}
                  name={v.name}
                  stroke={v.color}
                  strokeWidth={2}
                  dot={false}
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        );
      })}
    </div>
  );
}

/**
 * Single chart with every variable plotted together (dual y-axes).
 */
function UnifiedChart({ data }: { data: SensorDataRow[] }) {
  return (
    <ResponsiveContainer width="100%" height={440}>
      <LineChart data={data} margin={{ top: 10, right: 30, left: 12, bottom: 5 }}>
        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e2e8f0" />
        <XAxis dataKey="timeLabel" minTickGap={40} tick={{ fontSize: 12, fill: '#64748b' }} tickLine={false} />
        <YAxis yAxisId="left" width={52} tick={{ fontSize: 12, fill: '#64748b' }} tickLine={false} domain={['auto', 'auto']} />
        <YAxis yAxisId="right" orientation="right" width={52} tick={{ fontSize: 12, fill: '#64748b' }} tickLine={false} domain={['auto', 'auto']} />
        <Tooltip content={(props) => <SyncTooltip {...props} />} cursor={{ stroke: '#94a3b8', strokeDasharray: '4 4' }} />
        <Legend wrapperStyle={{ fontSize: '13px', paddingTop: '8px' }} />
        {VARIABLES.map(v => (
          <Line
            key={v.key}
            yAxisId={v.axis}
            type="monotone"
            dataKey={v.key}
            name={v.unit ? `${v.name} (${v.unit})` : v.name}
            stroke={v.color}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}

export default function SensorCharts({ data }: { data: SensorDataRow[] }) {
  const [unified, setUnified] = useState(false);

  return (
    <div>
      <div className="chart-toolbar">
        <div className="view-toggle" role="group" aria-label="Chart view mode">
          <button type="button" className={unified ? '' : 'active'} onClick={() => setUnified(false)}>
            Per-Variable
          </button>
          <button type="button" className={unified ? 'active' : ''} onClick={() => setUnified(true)}>
            Unified
          </button>
        </div>
        <span className="chart-hint">
          {unified
            ? 'All variables on a single chart'
            : 'Hover any graph to compare all variables at the same moment'}
        </span>
      </div>
      {unified ? <UnifiedChart data={data} /> : <StackedCharts data={data} />}
    </div>
  );
}
