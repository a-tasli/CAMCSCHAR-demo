import { NextResponse } from 'next/server';

// In-memory store for the uploaded data (in a real app, this would be a database)
// We export this so other routes or server components could theoretically read it, 
// though Next.js App Router isolates this per-request in edge runtime unless cached. 
// For this simple demo, we will just echo it back or rely on client-side state.

export async function POST(request: Request) {
  try {
    const data = await request.json();
    
    // In a production academic tool, you would save this to a Postgres database
    // or S3 bucket here.
    
    return NextResponse.json({ 
      message: 'Data uploaded successfully',
      count: Array.isArray(data) ? data.length : 1 
    }, { status: 200 });
  } catch (error) {
    return NextResponse.json({ error: 'Failed to process data' }, { status: 400 });
  }
}
