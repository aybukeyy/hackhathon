import { useState, useCallback, useEffect } from 'react'
import { X } from 'lucide-react'
import ChatWindow from './components/ChatWindow.jsx'
import InputBar from './components/InputBar.jsx'
import { sendMessage } from './services/chatService.js'
import { useVoiceOutput } from './hooks/useVoiceOutput.js'

const SESSION_ID = 'session-' + Math.random().toString(36).slice(2, 10)

function useLocation() {
  const [location, setLocation] = useState(null)

  useEffect(() => {
    if (!navigator.geolocation) return
    navigator.geolocation.getCurrentPosition(
      (pos) => setLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      () => {}
    )
  }, [])

  return { location }
}

const WELCOME = {
  id: 0,
  role: 'assistant',
  text: "Merhaba! Elektrik, doğalgaz veya su faturanızı ödemenize, İBB Çözüm Merkezi'ne şikayet başvurusu yapmanıza ya da sorularınızı yanıtlamama yardımcı olabilirim. Size nasıl yardımcı olabilirim?"
}

const QUICK_QUESTIONS = [
  'Elektrik faturamı ödemek istiyorum',
  "İBB'ye şikayet yapmak istiyorum",
  'Su faturamı sorgulamak istiyorum',
  'Doğalgaz faturamı ödemek istiyorum',
]


export default function App() {
  const [isOpen, setIsOpen]     = useState(false)
  const [bouncing, setBouncing] = useState(false)
  const [messages, setMessages] = useState([WELCOME])
  const [loading, setLoading]   = useState(false)
  const { speak }              = useVoiceOutput()
  const { location }           = useLocation()

  const addMsg = useCallback((role, text, routeData = null) => {
    setMessages((prev) => [...prev, { id: Date.now() + Math.random(), role, text, routeData }])
  }, [])

  const handleSend = useCallback(async (text) => {
    if (!text.trim() || loading) return
    addMsg('user', text)
    setLoading(true)
    try {
      const data = await sendMessage(SESSION_ID, text, location)
      addMsg('assistant', data.message, data.routeData)
      speak(data.routeData?.spokenSummary || data.message)
    } catch (err) {
      addMsg('assistant', `Hata: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }, [loading, addMsg, speak, location])

  const handleFileUpload = useCallback(async ({ filePath, fileName, error }) => {
    if (error) { addMsg('assistant', `Dosya yükleme hatası: ${error}`); return }
    addMsg('user', `📎 ${fileName}`)
    setLoading(true)
    try {
      const data = await sendMessage(SESSION_ID, `FOTO_YUKLENDI:${filePath}`, location)
      addMsg('assistant', data.message)
      speak(data.message)
    } catch (err) {
      addMsg('assistant', `Hata: ${err.message}`)
    } finally {
      setLoading(false)
    }
  }, [loading, addMsg, speak, location])

  return (
    <>

      {/* Yüzen buton */}
      <button
        onClick={() => {
          setBouncing(true)
          setTimeout(() => setBouncing(false), 400)
          setIsOpen((v) => !v)
        }}
        className="fixed bottom-6 right-8 z-40 focus:outline-none group animate-float"
      >
        <img
          src="/logo.png"
          alt="İBB Asistan"
          className={`w-36 h-36 object-contain drop-shadow-2xl transition-all duration-300 group-hover:-translate-y-2 ${bouncing ? 'animate-bounce-up' : ''}`}
        />
      </button>

      {/* Chat bubble penceresi */}
      <div className={`fixed bottom-36 right-44 z-50 flex flex-row-reverse items-end gap-3 transition-all duration-300 origin-bottom-right ${
        isOpen ? 'opacity-100 scale-100 pointer-events-auto' : 'opacity-0 scale-90 pointer-events-none'
      }`}>

        {/* Ana chat baloncuğu */}
        <div className="w-[420px] md:w-[480px] h-[620px] bg-white rounded-2xl shadow-2xl border border-gray-200 flex flex-col overflow-hidden">

          {/* Header */}
          <div className="p-3 flex items-center justify-between bg-gradient-to-r from-blue-500 via-blue-400 to-sky-400 flex-shrink-0">
            <div className="flex items-center gap-2">
              <img src="/logo.png" alt="İBB" className="w-8 h-8 object-contain" />
              <div>
                <p className="text-white font-semibold text-sm leading-tight">İBBot</p>
                <div className="flex items-center gap-1">
                  <div className="w-1.5 h-1.5 rounded-full bg-green-400 animate-pulse" />
                  <p className="text-blue-100 text-[10px]">Çevrimiçi</p>
                </div>
              </div>
            </div>
            <button
              onClick={() => setIsOpen(false)}
              className="p-1.5 rounded-full bg-white/10 text-white hover:bg-white/20 transition-all"
            >
              <X size={16} />
            </button>
          </div>

          {/* Hızlı sorular — ilk mesaj gönderilince kaybolur */}
          {messages.length <= 1 && (
            <div className="px-3 py-2 border-b border-gray-100 grid grid-cols-2 gap-1.5 flex-shrink-0">
              {QUICK_QUESTIONS.map((q, i) => (
                <button
                  key={i}
                  onClick={() => handleSend(q)}
                  disabled={loading}
                  className="text-left text-xs px-3 py-2.5 rounded-xl bg-blue-50 text-blue-600 border border-blue-200 hover:bg-blue-100 transition-all disabled:opacity-50 font-medium"
                >
                  {q}
                </button>
              ))}
            </div>
          )}

          {/* Mesajlar */}
          <ChatWindow messages={messages} loading={loading} speak={speak} />

          {/* Input */}
          <InputBar onSend={handleSend} onFileUpload={handleFileUpload} disabled={loading} />
        </div>

      </div>
    </>
  )
}
