import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { API_URL } from '../api';

/**
 * In-app ticket chat (§53): volunteer↔recipient messages scoped to one ticket.
 * Polls GET /tickets/{id}/messages every 4s and POSTs new ones. `me` is the
 * caller's role ('needy' | 'volunteer') used to align bubbles left/right.
 */
const TicketChat = ({ ticketId, token, me, ns = 'volunteer' }) => {
  const { t } = useTranslation();
  const [messages, setMessages] = useState([]);
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const lastIdRef = useRef(0);
  const bottomRef = useRef(null);

  const authHeader = { Authorization: `Bearer ${token}` };

  const poll = useCallback(async () => {
    if (!ticketId) return;
    try {
      const res = await fetch(`${API_URL}/tickets/${ticketId}/messages?after_id=${lastIdRef.current}`, { headers: authHeader });
      if (!res.ok) return;
      const data = await res.json();
      if (Array.isArray(data) && data.length) {
        lastIdRef.current = data[data.length - 1].id;
        setMessages(prev => [...prev, ...data]);
      }
    } catch { /* offline — keep what we have */ }
  }, [ticketId, token]);

  useEffect(() => {
    lastIdRef.current = 0;
    setMessages([]);
    poll();
    const iv = setInterval(poll, 4000);
    return () => clearInterval(iv);
  }, [ticketId, poll]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length]);

  const send = async (e) => {
    e.preventDefault();
    const body = draft.trim();
    if (!body || sending) return;
    setSending(true);
    try {
      const res = await fetch(`${API_URL}/tickets/${ticketId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeader },
        body: JSON.stringify({ body }),
      });
      if (res.ok) {
        const msg = await res.json();
        lastIdRef.current = msg.id;
        setMessages(prev => [...prev, msg]);
        setDraft('');
      } else {
        const err = await res.json().catch(() => ({}));
        if (err.detail) alert(err.detail);
      }
    } catch { /* swallow */ }
    setSending(false);
  };

  return (
    <div className="ticket-chat">
      <div className="ticket-chat-log">
        {messages.length === 0 ? (
          <p className="ticket-chat-empty">{t(`${ns}.chat_empty`)}</p>
        ) : (
          messages.map(m => (
            <div key={m.id} className={`ticket-chat-msg ${m.sender_role === me ? 'mine' : 'theirs'}`}>
              <span>{m.body}</span>
            </div>
          ))
        )}
        <div ref={bottomRef} />
      </div>
      <form className="ticket-chat-input" onSubmit={send}>
        <input
          type="text"
          value={draft}
          maxLength={2000}
          placeholder={t(`${ns}.chat_placeholder`)}
          onChange={e => setDraft(e.target.value)}
        />
        <button type="submit" className="btn-small" disabled={sending || !draft.trim()}>{t(`${ns}.chat_send`)}</button>
      </form>
    </div>
  );
};

export default TicketChat;
