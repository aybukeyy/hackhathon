import { useState } from 'react'
import { Volume2, VolumeX } from 'lucide-react'
import MapPanel from './MapPanel.jsx'

const URL_REGEX = /(https?:\/\/[^\s]+\.(?:jpg|jpeg|png|gif|webp))/gi

function renderText(text) {
  const parts = text.split(URL_REGEX)
  return parts.map((part, i) =>
    URL_REGEX.test(part)
      ? <img key={i} src={part} alt="" className="mt-2 rounded-xl max-w-full max-h-48 object-contain" onError={e => e.target.style.display='none'} />
      : <span key={i}>{part}</span>
  )
}

export default function MessageBubble({ role, text, speak, routeData, image }) {
  const [playing, setPlaying] = useState(false)

  const handleSpeak = () => {
    if (!speak) return
    window.speechSynthesis.cancel()
    if (playing) { setPlaying(false); return }
    setPlaying(true)
    speak(text)
    const check = setInterval(() => {
      if (!window.speechSynthesis.speaking) {
        setPlaying(false)
        clearInterval(check)
      }
    }, 300)
  }

  return (
    <div className={`message ${role}`}>
      <div className="flex flex-col">
        <div className="bubble">
          {image && <img src={image} alt="" className="mb-1 rounded-xl max-w-full max-h-48 object-contain" />}
          {renderText(text)}
        </div>
        {routeData && <MapPanel routeData={routeData} />}
        {role === 'assistant' && speak && (
          <button
            onClick={handleSpeak}
            className="mt-1 self-end flex items-center gap-1 text-[10px] text-gray-400 hover:text-blue-500 transition-colors"
            title={playing ? 'Durdur' : 'Sesli dinle'}
          >
            {playing
              ? <VolumeX size={12} className="text-blue-500" />
              : <Volume2 size={12} />
            }
          </button>
        )}
      </div>
    </div>
  )
}
