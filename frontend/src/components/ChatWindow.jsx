import { useEffect, useRef } from 'react'
import MessageBubble from './MessageBubble.jsx'

export default function ChatWindow({ messages, loading, speak }) {
  const bottomRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  return (
    <div className="chat-window">
      {messages.map((msg) => (
        <MessageBubble key={msg.id} role={msg.role} text={msg.text} speak={speak} routeData={msg.routeData} image={msg.image} />
      ))}

      {loading && (
        <div className="message assistant">
          <div className="bubble typing">
            <span /><span /><span />
          </div>
        </div>
      )}

      <div ref={bottomRef} />
    </div>
  )
}
