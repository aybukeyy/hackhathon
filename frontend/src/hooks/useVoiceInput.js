import { useState, useCallback, useRef } from 'react'

export function useVoiceInput(onResult) {
  const [listening, setListening] = useState(false)
  const recRef = useRef(null)

  const startListening = useCallback(() => {
    const SpeechRecognition =
      window.SpeechRecognition || window.webkitSpeechRecognition
    if (!SpeechRecognition) {
      alert('Speech recognition is not supported in this browser.')
      return
    }

    const rec = new SpeechRecognition()
    rec.lang = 'tr-TR'
    rec.interimResults = false
    rec.maxAlternatives = 1

    rec.onstart  = () => setListening(true)
    rec.onend    = () => setListening(false)
    rec.onerror  = () => setListening(false)
    rec.onresult = (e) => {
      const transcript = e.results[0][0].transcript
      onResult(transcript)
    }

    recRef.current = rec
    rec.start()
  }, [onResult])

  const stopListening = useCallback(() => {
    recRef.current?.stop()
  }, [])

  return { listening, startListening, stopListening }
}
