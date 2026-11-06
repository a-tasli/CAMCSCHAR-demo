import { NextResponse } from 'next/server';
import fs from 'fs';
import path from 'path';

export async function POST(request: Request) {
  try {
    const data = await request.json();
    
    const dataDir = path.join(process.cwd(), 'data');
    if (!fs.existsSync(dataDir)) {
      fs.mkdirSync(dataDir, { recursive: true });
    }
    
    const filePath = path.join(dataDir, 'latest_experiment.json');
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2));
    
    return NextResponse.json({ 
      message: 'Data uploaded successfully',
      count: Array.isArray(data) ? data.length : 1 
    }, { status: 200 });
  } catch (error: unknown) {
    console.error("Upload error:", error);
    return NextResponse.json({ error: 'Failed to process data' }, { status: 400 });
  }
}
