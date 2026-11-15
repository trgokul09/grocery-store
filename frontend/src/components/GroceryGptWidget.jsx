import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  Bot, Check, Copy, Download, ExternalLink, History, Maximize2, MessageSquarePlus,
  Minimize2, Send, Sparkles, Trash2, User as UserIcon, X
} from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { cn, useToast } from './ui.jsx'
import { errMsg } from '../utils'

export const DEFAULT_WELCOME =
  "Hey! I'm GroceryGPT, your digital business partner. Ask me about today's sales, profits, low stock, expenses, or what to focus on next — I'll check your live store records and explain what's going on."

/**
 * Lightweight, safe Markdown-like renderer for GroceryGPT responses.
 * Renders headings (###, ####), bullet lists (- / 1.), bold (**text**), italic (*text*), inline code (`code`), and paragraphs.
 */
export function FormattedAiText({ text }) {
  if (!text) return null
  const lines = text.split('\n')

  const renderInline = (line) => {
    const parts = line.split(/(\*\*.*?\*\*|\*[^*]+?\*|`.*?`)/g)
    return parts.map((part, idx) => {
      if (part.startsWith('**') && part.endsWith('**')) {
        return <strong key={idx} className="font-semibold text-slate-900">{part.slice(2, -2)}</strong>
      }
      if (part.startsWith('*') && part.endsWith('*') && part.length > 2) {
        return <em key={idx} className="italic text-slate-600">{part.slice(1, -1)}</em>
      }
      if (part.startsWith('`') && part.endsWith('`')) {
        return <code key={idx} className="rounded bg-emerald-50 px-1.5 py-0.5 font-mono text-xs font-medium text-emerald-800">{part.slice(1, -1)}</code>
      }
      return part
    })
  }

  return (
    <div className="space-y-1.5 text-sm leading-relaxed text-slate-700">
      {lines.map((raw, i) => {
        const line = raw.trim()
        if (!line) return <div key={i} className="h-1" />
        if (line.startsWith('### ')) {
          return <h4 key={i} className="pt-1 text-sm font-bold text-slate-900">{renderInline(line.slice(4))}</h4>
        }
        if (line.startsWith('#### ')) {
          return <h5 key={i} className="pt-1 text-xs font-bold uppercase tracking-wider text-emerald-700">{renderInline(line.slice(5))}</h5>
        }
        if (line.startsWith('- ') || line.startsWith('* ')) {
          return (
            <div key={i} className="flex items-start gap-2 pl-1">
              <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500" />
              <span className="flex-1">{renderInline(line.slice(2))}</span>
            </div>
          )
        }
        const numMatch = line.match(/^(\d+)\.\s+(.*)$/)
        if (numMatch) {
          return (
            <div key={i} className="flex items-start gap-2 pl-1">
              <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-[11px] font-bold text-emerald-800">
                {numMatch[1]}
              </span>
              <span className="flex-1 pt-0.5">{renderInline(numMatch[2])}</span>
            </div>
          )
        }
        return <p key={i}>{renderInline(line)}</p>
      })}
    </div>
  )
}

export function AiMessageBubble({ msg, onAskFollowUp, onNavigate, compact = false }) {
  const isUser = msg.role === 'USER'
  const toast = useToast()
  const [copied, setCopied] = useState(false)

  const copyText = () => {
    navigator.clipboard.writeText(msg.content || '')
    setCopied(true)
    toast('Response copied to clipboard')
    setTimeout(() => setCopied(false), 2000)
  }

  const downloadReport = async (dl) => {
    try {
      const r = await api.get(dl.endpoint, { responseType: 'blob' })
      const url = window.URL.createObjectURL(new Blob([r.data]))
      const a = document.createElement('a')
      a.href = url
      a.download = dl.filename || 'report.csv'
      document.body.appendChild(a)
      a.click()
      a.remove()
      window.URL.revokeObjectURL(url)
      toast(`Downloaded ${dl.filename}`)
    } catch (e) {
      toast(errMsg(e), 'error')
    }
  }

  if (isUser) {
    return (
      <div className="flex justify-end">
        <div className="flex max-w-[85%] items-end gap-2">
          <div className="rounded-2xl rounded-br-none bg-emerald-600 px-4 py-2.5 text-sm text-white shadow-sm">
            {msg.content}
          </div>
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-slate-200 text-slate-700">
            <UserIcon className="h-4 w-4" />
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="flex justify-start">
      <div className="flex w-full max-w-[96%] items-start gap-2.5">
        <div className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-emerald-500 to-teal-600 text-white shadow-sm">
          <Bot className="h-4 w-4" />
        </div>
        <div className="min-w-0 flex-1 space-y-3 rounded-2xl rounded-tl-none border border-slate-200 bg-white p-3.5 shadow-sm">
          <FormattedAiText text={msg.content} />

          {/* Formatted Data Tables */}
          {msg.tables && msg.tables.length > 0 && (
            <div className="space-y-3 pt-1">
              {msg.tables.map((tbl, idx) => (
                <div key={idx} className="overflow-hidden rounded-xl border border-slate-200 bg-slate-50/50">
                  {tbl.title && (
                    <div className="border-b border-slate-200 bg-slate-100/80 px-3 py-1.5 text-xs font-semibold text-slate-700">
                      {tbl.title}
                    </div>
                  )}
                  <div className="max-h-60 overflow-x-auto overflow-y-auto">
                    <table className="w-full text-left text-xs">
                      <thead className="sticky top-0 bg-slate-100 text-[11px] font-semibold uppercase text-slate-600">
                        <tr>
                          {(tbl.columns || []).map((col, cIdx) => (
                            <th key={cIdx} className="whitespace-nowrap px-2.5 py-2">{col}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-200 bg-white">
                        {(tbl.rows || []).map((row, rIdx) => (
                          <tr key={rIdx} className="hover:bg-emerald-50/40">
                            {row.map((cell, cellIdx) => (
                              <td key={cellIdx} className={cn('whitespace-nowrap px-2.5 py-1.5 text-slate-700', cellIdx === 0 && 'font-medium text-slate-900')}>
                                {cell}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Action Links & Report Downloads */}
          {((msg.navLinks && msg.navLinks.length > 0) || (msg.downloads && msg.downloads.length > 0)) && (
            <div className="flex flex-wrap items-center gap-1.5 pt-1">
              {(msg.navLinks || []).map((nav, nIdx) => (
                <button
                  key={nIdx}
                  onClick={() => onNavigate(nav.path)}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700 transition hover:bg-emerald-100"
                >
                  <ExternalLink className="h-3.5 w-3.5" />
                  {nav.label}
                </button>
              ))}
              {(msg.downloads || []).map((dl, dIdx) => (
                <button
                  key={dIdx}
                  onClick={() => downloadReport(dl)}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-700 transition hover:bg-slate-200"
                >
                  <Download className="h-3.5 w-3.5" />
                  {dl.label}
                </button>
              ))}
            </div>
          )}

          {/* Follow-up question chips */}
          {msg.followUpSuggestions && msg.followUpSuggestions.length > 0 && (
            <div className="border-t border-slate-100 pt-2">
              <p className="mb-1.5 text-[11px] font-medium text-slate-400">Suggested follow-ups:</p>
              <div className="flex flex-wrap gap-1.5">
                {msg.followUpSuggestions.slice(0, compact ? 3 : 4).map((q, qIdx) => (
                  <button
                    key={qIdx}
                    onClick={() => onAskFollowUp(q)}
                    className="rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-left text-[11px] text-slate-600 transition hover:border-emerald-300 hover:bg-emerald-50 hover:text-emerald-700"
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Footer: Copy button + tools badge */}
          <div className="flex items-center justify-between border-t border-slate-100 pt-1.5 text-[11px] text-slate-400">
            <div className="flex items-center gap-1.5 truncate">
              {msg.toolsInvoked && msg.toolsInvoked.length > 0 && (
                <span className="truncate rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] text-slate-500">
                  Source: {msg.toolsInvoked.join(', ')}
                </span>
              )}
            </div>
            <button
              onClick={copyText}
              className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
              title="Copy response"
            >
              {copied ? <Check className="h-3.5 w-3.5 text-emerald-600" /> : <Copy className="h-3.5 w-3.5" />}
              <span>{copied ? 'Copied' : 'Copy'}</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * Floating bottom-right GroceryGPT Chatbot Widget.
 * Appears on all authenticated screens (except when already on /grocery-gpt full page).
 */
export default function GroceryGptWidget() {
  const { user } = useAuth()
  const nav = useNavigate()
  const location = useLocation()
  const toast = useToast()

  const [open, setOpen] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [welcome, setWelcome] = useState(DEFAULT_WELCOME)
  const [quickPrompts, setQuickPrompts] = useState([])
  const [conversations, setConversations] = useState([])
  const [conversationId, setConversationId] = useState(null)
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)

  const bottomRef = useRef(null)

  useEffect(() => {
    if (!user || !open) return
    api.get('/ai/suggestions').then((r) => {
      if (r.data?.welcomeMessage) setWelcome(r.data.welcomeMessage)
      setQuickPrompts(r.data?.quickSuggestions || [])
    }).catch(() => {})
    loadConversations()
  }, [user, open])

  useEffect(() => {
    if (open) {
      setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }), 60)
    }
  }, [messages, busy, open])

  const loadConversations = () => {
    api.get('/ai/conversations').then((r) => setConversations(r.data || [])).catch(() => {})
  }

  const selectConversation = async (id) => {
    try {
      const r = await api.get(`/ai/conversations/${id}`)
      setConversationId(r.data.id)
      setMessages(r.data.messages || [])
      setShowHistory(false)
    } catch (e) {
      toast(errMsg(e), 'error')
    }
  }

  const startNewChat = () => {
    setConversationId(null)
    setMessages([])
    setShowHistory(false)
  }

  const clearCurrentOrAll = async () => {
    try {
      if (conversationId) {
        await api.delete(`/ai/conversations/${conversationId}`)
        toast('Conversation cleared')
      } else {
        await api.delete('/ai/conversations')
        toast('All chat history cleared')
      }
      setConversationId(null)
      setMessages([])
      loadConversations()
    } catch (e) {
      toast(errMsg(e), 'error')
    }
  }

  const sendMessage = async (textOverride) => {
    const q = (textOverride ?? input).trim()
    if (!q || busy) return
    if (!textOverride) setInput('')

    const optimisticUser = { id: Date.now(), role: 'USER', content: q }
    setMessages((prev) => [...prev, optimisticUser])
    setBusy(true)

    try {
      const r = await api.post('/ai/chat', {
        conversationId,
        message: q,
        currentPage: location.pathname
      })
      const d = r.data
      setConversationId(d.conversationId)
      const assistantMsg = {
        id: d.messageId || Date.now() + 1,
        role: 'ASSISTANT',
        content: d.answer,
        intent: d.intent,
        toolsInvoked: d.toolsInvoked,
        tables: d.tables,
        navLinks: d.navLinks,
        downloads: d.downloads,
        followUpSuggestions: d.followUpSuggestions,
        createdAt: d.timestamp
      }
      setMessages((prev) => [...prev, assistantMsg])
      loadConversations()
    } catch (e) {
      const errText = errMsg(e)
      setMessages((prev) => [
        ...prev,
        {
          id: Date.now() + 2,
          role: 'ASSISTANT',
          content: `**Unable to complete request**: ${errText}. Please try again.`
        }
      ])
    } finally {
      setBusy(false)
    }
  }

  // Hide floating widget when already on the dedicated /grocery-gpt page
  if (!user || location.pathname === '/grocery-gpt') return null

  return (
    <>
      {/* Floating Launcher Button */}
      {!open && (
        <button
          onClick={() => setOpen(true)}
          aria-label="Open GroceryGPT Assistant"
          className="fixed bottom-20 right-5 z-40 flex items-center gap-2.5 rounded-full bg-gradient-to-r from-emerald-600 to-teal-600 px-4 py-3 text-sm font-semibold text-white shadow-lg shadow-emerald-900/25 transition hover:scale-105 hover:from-emerald-500 hover:to-teal-500 lg:bottom-6 lg:right-6"
        >
          <span className="relative flex h-6 w-6 items-center justify-center rounded-full bg-white/20">
            <Sparkles className="h-4 w-4" />
            <span className="absolute -right-0.5 -top-0.5 h-2 w-2 rounded-full bg-amber-300" />
          </span>
          <span>GroceryGPT</span>
        </button>
      )}

      {/* Expandable Floating Chat Window */}
      {open && (
        <div
          className={cn(
            'fixed z-50 flex flex-col overflow-hidden rounded-2xl border border-slate-200 bg-slate-50 shadow-2xl transition-all duration-200',
            expanded
              ? 'bottom-4 right-4 left-4 top-16 lg:left-64 lg:bottom-6 lg:right-6'
              : 'bottom-4 right-4 h-[600px] max-h-[82vh] w-[calc(100vw-2rem)] sm:w-[430px] lg:bottom-6 lg:right-6'
          )}
        >
          {/* Header */}
          <div className="flex items-center justify-between bg-gradient-to-r from-slate-900 via-emerald-950 to-slate-900 px-4 py-3 text-white">
            <div className="flex items-center gap-2.5">
              <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-emerald-500/20 text-emerald-400 ring-1 ring-emerald-400/30">
                <Bot className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-1.5">
                  <h3 className="text-sm font-bold tracking-tight">GroceryGPT</h3>
                  <span className="rounded-full bg-emerald-500/20 px-2 py-0.5 text-[10px] font-semibold text-emerald-300">
                    Live DB
                  </span>
                </div>
                <p className="text-[11px] text-slate-300">AI Business & Application Assistant</p>
              </div>
            </div>
            <div className="flex items-center gap-1">
              <button
                onClick={startNewChat}
                title="New Conversation"
                className="rounded-lg p-1.5 text-slate-300 hover:bg-white/10 hover:text-white"
              >
                <MessageSquarePlus className="h-4 w-4" />
              </button>
              <button
                onClick={() => setShowHistory((v) => !v)}
                title="Conversation History"
                className={cn('rounded-lg p-1.5 text-slate-300 hover:bg-white/10 hover:text-white', showHistory && 'bg-white/15 text-white')}
              >
                <History className="h-4 w-4" />
              </button>
              <button
                onClick={clearCurrentOrAll}
                title="Clear Conversation"
                className="rounded-lg p-1.5 text-slate-300 hover:bg-white/10 hover:text-rose-300"
              >
                <Trash2 className="h-4 w-4" />
              </button>
              <button
                onClick={() => setExpanded((v) => !v)}
                title={expanded ? 'Restore size' : 'Expand window'}
                className="rounded-lg p-1.5 text-slate-300 hover:bg-white/10 hover:text-white"
              >
                {expanded ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
              </button>
              <button
                onClick={() => { setOpen(false); nav('/grocery-gpt') }}
                title="Open Full Workspace"
                className="rounded-lg p-1.5 text-slate-300 hover:bg-white/10 hover:text-emerald-300"
              >
                <ExternalLink className="h-4 w-4" />
              </button>
              <button
                onClick={() => setOpen(false)}
                title="Close"
                className="rounded-lg p-1.5 text-slate-300 hover:bg-white/10 hover:text-white"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
          </div>

          {/* History Drawer Overlay */}
          {showHistory && (
            <div className="border-b border-slate-200 bg-white p-3">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">Recent Conversations</span>
                <button onClick={() => setShowHistory(false)} className="text-xs text-slate-400 hover:text-slate-700">Close</button>
              </div>
              {conversations.length === 0 ? (
                <p className="py-2 text-xs text-slate-400">No saved conversations yet.</p>
              ) : (
                <div className="max-h-40 space-y-1 overflow-y-auto">
                  {conversations.map((c) => (
                    <button
                      key={c.id}
                      onClick={() => selectConversation(c.id)}
                      className={cn(
                        'flex w-full items-center justify-between rounded-lg px-2.5 py-1.5 text-left text-xs transition',
                        conversationId === c.id ? 'bg-emerald-50 font-semibold text-emerald-700' : 'text-slate-700 hover:bg-slate-100'
                      )}
                    >
                      <span className="truncate">{c.title}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Messages Stream */}
          <div className="flex-1 space-y-3 overflow-y-auto p-3.5">
            {/* Always show the official welcome message at the top */}
            <AiMessageBubble
              msg={{
                id: 'welcome',
                role: 'ASSISTANT',
                content: welcome
              }}
              onAskFollowUp={(q) => sendMessage(q)}
              onNavigate={(path) => { setOpen(false); nav(path) }}
              compact
            />

            {/* Suggested Starter Questions when conversation is fresh */}
            {messages.length === 0 && quickPrompts.length > 0 && (
              <div className="rounded-xl border border-emerald-100 bg-emerald-50/50 p-3">
                <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold text-emerald-800">
                  <Sparkles className="h-3.5 w-3.5 text-emerald-600" />
                  Try asking GroceryGPT:
                </p>
                <div className="flex flex-wrap gap-1.5">
                  {quickPrompts.map((q, idx) => (
                    <button
                      key={idx}
                      onClick={() => sendMessage(q)}
                      className="rounded-lg border border-emerald-200/80 bg-white px-2.5 py-1.5 text-left text-xs font-medium text-slate-700 shadow-sm transition hover:border-emerald-400 hover:bg-emerald-50 hover:text-emerald-800"
                    >
                      {q}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {messages.map((m) => (
              <AiMessageBubble
                key={m.id}
                msg={m}
                onAskFollowUp={(q) => sendMessage(q)}
                onNavigate={(path) => { setOpen(false); nav(path) }}
                compact
              />
            ))}

            {/* Typing Animation / Loading Indicator */}
            {busy && (
              <div className="flex items-center gap-2.5">
                <div className="flex h-7 w-7 items-center justify-center rounded-full bg-emerald-600 text-white">
                  <Bot className="h-4 w-4 animate-pulse" />
                </div>
                <div className="flex items-center gap-1.5 rounded-2xl rounded-tl-none border border-slate-200 bg-white px-4 py-2.5 text-xs text-slate-500 shadow-sm">
                  <span className="h-2 w-2 animate-bounce rounded-full bg-emerald-500 [animation-delay:-0.3s]" />
                  <span className="h-2 w-2 animate-bounce rounded-full bg-emerald-500 [animation-delay:-0.15s]" />
                  <span className="h-2 w-2 animate-bounce rounded-full bg-emerald-500" />
                  <span className="ml-1 font-medium text-slate-600">GroceryGPT is analyzing live store data...</span>
                </div>
              </div>
            )}
            <div ref={bottomRef} />
          </div>

          {/* Input Bar */}
          <form
            onSubmit={(e) => { e.preventDefault(); sendMessage() }}
            className="border-t border-slate-200 bg-white p-3"
          >
            <div className="flex items-center gap-2">
              <input
                type="text"
                className="input flex-1 text-sm"
                placeholder="Ask about sales, inventory, attendance, payroll, or how-to..."
                value={input}
                onChange={(e) => setInput(e.target.value)}
                disabled={busy}
              />
              <button
                type="submit"
                disabled={busy || !input.trim()}
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-emerald-600 text-white transition hover:bg-emerald-700 disabled:opacity-50"
              >
                <Send className="h-4 w-4" />
              </button>
            </div>
          </form>
        </div>
      )}
    </>
  )
}
