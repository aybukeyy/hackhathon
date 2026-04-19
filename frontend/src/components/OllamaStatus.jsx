import { useEffect, useState } from 'react'
import { fetchOllamaHealth } from '../services/chatService.js'

export default function OllamaStatus() {
  const [health, setHealth] = useState(null)

  useEffect(() => {
    fetchOllamaHealth()
      .then(setHealth)
      .catch(() => setHealth({ status: 'DOWN', reachable: false, activeModel: 'none' }))
  }, [])

  if (!health) return null

  const up = health.status === 'UP'
  return (
    <div className={`ollama-status ${up ? 'up' : 'down'}`}>
      <span className="status-dot" />
      {up ? `Ollama · ${health.activeModel}` : 'Ollama offline'}
    </div>
  )
}
