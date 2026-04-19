import { useState, useCallback, useRef } from 'react'
import { useVoiceInput } from '../hooks/useVoiceInput.js'
import { uploadFile } from '../services/chatService.js'

export default function InputBar({ onSend, onFileUpload, disabled }) {
  const [text, setText]           = useState('')
  const [uploading, setUploading] = useState(false)
  const [uploadPct, setUploadPct] = useState(0)
  const fileInputRef              = useRef(null)

  const submit = useCallback(() => {
    const trimmed = text.trim()
    if (!trimmed || disabled || uploading) return
    onSend(trimmed)
    setText('')
  }, [text, onSend, disabled, uploading])

  const onKeyDown = useCallback((e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }, [submit])

  const { listening, startListening, stopListening } = useVoiceInput((transcript) => {
    onSend(transcript)
  })

  // Ataç butonu tıklandığında gizli file input'u tetikle
  const handleAttachClick = useCallback(() => {
    if (disabled || uploading) return
    fileInputRef.current?.click()
  }, [disabled, uploading])

  // Dosya seçildiğinde yükleme başlat
  const handleFileChange = useCallback(async (e) => {
    const file = e.target.files?.[0]
    if (!file) return

    // File input'u sıfırla — aynı dosya tekrar seçilebilsin
    e.target.value = ''

    // Boyut ön kontrolü (10MB)
    const MAX_SIZE = 10 * 1024 * 1024
    if (file.size > MAX_SIZE) {
      if (typeof onFileUpload === 'function') {
        onFileUpload({ error: 'Dosya boyutu 10MB sınırını aşıyor.' })
      }
      return
    }

    // Uzantı ön kontrolü
    const allowedExt = ['jpeg', 'jpg', 'png', 'pdf']
    const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
    if (!allowedExt.includes(ext)) {
      if (typeof onFileUpload === 'function') {
        onFileUpload({ error: 'Yalnızca jpeg, jpg, png ve pdf dosyaları desteklenir.' })
      }
      return
    }

    const imagePreview = file.type.startsWith('image/') ? URL.createObjectURL(file) : null

    // Fotoğrafı upload bitmeden hemen sohbette göster
    if (typeof onFileUpload === 'function') {
      onFileUpload({ fileName: file.name, imagePreview, previewOnly: true })
    }

    setUploading(true)
    setUploadPct(0)

    try {
      const result = await uploadFile(file, (pct) => setUploadPct(pct))
      if (typeof onFileUpload === 'function') {
        onFileUpload({ filePath: result.filePath, fileName: file.name })
      }
    } catch (err) {
      if (typeof onFileUpload === 'function') {
        onFileUpload({ error: err.message })
      }
    } finally {
      setUploading(false)
      setUploadPct(0)
    }
  }, [onFileUpload])

  const isDisabled = disabled || uploading

  return (
    <div className="input-bar">
      {/* Gizli dosya input'u */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".jpeg,.jpg,.png,.pdf"
        style={{ display: 'none' }}
        onChange={handleFileChange}
        aria-hidden="true"
      />

      <textarea
        className="message-input"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder="Mesajınızı yazın… (Göndermek için Enter)"
        disabled={isDisabled}
        rows={1}
      />

      {/* Ataç butonu */}
      <button
        className={`icon-btn attach-btn${uploading ? ' uploading' : ''}`}
        onClick={handleAttachClick}
        disabled={isDisabled}
        title={uploading ? `Yükleniyor… %${uploadPct}` : 'Dosya ekle'}
        aria-label={uploading ? `Dosya yükleniyor, %${uploadPct}` : 'Dosya ekle'}
      >
        {uploading ? (
          <span className="upload-progress">{uploadPct}%</span>
        ) : (
          '📎'
        )}
      </button>

      <button
        className={`icon-btn voice-btn${listening ? ' listening' : ''}`}
        onClick={listening ? stopListening : startListening}
        disabled={isDisabled}
        title={listening ? 'Kaydı durdur' : 'Sesli giriş'}
      >
        {listening ? '⏹' : '🎤'}
      </button>

      <button
        className="send-btn"
        onClick={submit}
        disabled={isDisabled || !text.trim()}
      >
        Gönder
      </button>
    </div>
  )
}
