import { NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';

export async function GET() {
  try {
    const filePath = path.join(process.cwd(), 'data', 'latest_experiment.json');
    if (fs.existsSync(filePath)) {
      const fileData = fs.readFileSync(filePath, 'utf-8');
      return NextResponse.json(JSON.parse(fileData), { status: 200 });
    } else {
      return NextResponse.json({ error: 'No data found' }, { status: 404 });
    }
  } catch {
    return NextResponse.json({ error: 'Failed to read data' }, { status: 500 });
  }
}
