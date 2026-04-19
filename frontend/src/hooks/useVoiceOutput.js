import { useCallback } from 'react'

export function useVoiceOutput() {
  const speak = useCallback((text) => {
    if (!window.speechSynthesis) return
    window.speechSynthesis.cancel()
    const utterance = new SpeechSynthesisUtterance(text)
    utterance.lang  = 'tr-TR'
    utterance.rate  = 1.0
    utterance.pitch = 1.0
    window.speechSynthesis.speak(utterance)
  }, [])

  const cancel = useCallback(() => {
    window.speechSynthesis?.cancel()
  }, [])

  return { speak, cancel }
}
