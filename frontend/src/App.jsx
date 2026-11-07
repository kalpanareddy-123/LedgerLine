import React, { useEffect, useState } from 'react';

export default function App() {
  const [health, setHealth] = useState(null);

  useEffect(() => {
    fetch('/api/health')
      .then(res => res.json())
      .then(data => setHealth(data))
      .catch(err => setHealth({ status: 'OFFLINE', error: err.message }));
  }, []);

  return (
    <div style={{ padding: '2rem', fontFamily: 'Inter, sans-serif' }}>
      <h1>LedgerLine Banking Engine</h1>
      <p>Status: {health ? JSON.stringify(health) : 'Checking health...'}</p>
    </div>
  );
}
