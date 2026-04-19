export async function sendMessage(sessionId, message, location = null) {
  const body = { sessionId, message }
  if (location) {
    body.lat = location.lat
    body.lng = location.lng
  }
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`Server error ${res.status}: ${text}`)
  }
  return res.json()
}

export async function fetchOllamaHealth() {
  const res = await fetch('/health/ollama')
  if (!res.ok) throw new Error('Health check failed')
  return res.json()
}

export async function fetchTrafficData() {
  const res = await fetch('/api/traffic')
  if (!res.ok) throw new Error('Traffic fetch failed')
  return res.json()
}

/**
 * Dosyayı /api/upload endpoint'ine yükler.
 * @param {File} file - Yüklenecek dosya nesnesi
 * @param {function} onProgress - İlerleme callback'i (0-100 arası yüzde) — opsiyonel
 * @returns {Promise<{filePath: string, fileName: string, fileSize: number}>}
 */
export function uploadFile(file, onProgress) {
  return new Promise((resolve, reject) => {
    const formData = new FormData()
    formData.append('file', file)

    const xhr = new XMLHttpRequest()

    // İlerleme bildirimi
    if (typeof onProgress === 'function') {
      xhr.upload.addEventListener('progress', (e) => {
        if (e.lengthComputable) {
          const pct = Math.round((e.loaded / e.total) * 100)
          onProgress(pct)
        }
      })
    }

    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          const data = JSON.parse(xhr.responseText)
          resolve(data)
        } catch {
          reject(new Error('Sunucu yanıtı geçersiz JSON içeriyor.'))
        }
      } else {
        let errMsg = `Yükleme hatası (HTTP ${xhr.status})`
        try {
          const data = JSON.parse(xhr.responseText)
          if (data.error) errMsg = data.error
        } catch { /* yanıt JSON değilse ham metin göster */ }
        reject(new Error(errMsg))
      }
    })

    xhr.addEventListener('error', () => {
      reject(new Error('Dosya yükleme sırasında ağ hatası oluştu.'))
    })

    xhr.addEventListener('abort', () => {
      reject(new Error('Dosya yükleme iptal edildi.'))
    })

    xhr.open('POST', '/api/upload')
    xhr.send(formData)
  })
}
