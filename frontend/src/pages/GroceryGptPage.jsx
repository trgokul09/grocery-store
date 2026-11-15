import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Bot, BookOpen, Database, MessageSquare, MessageSquarePlus, Send,
  ShieldCheck, Sparkles, Trash2
} from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { Button, cn, useToast } from '../components/ui.jsx'
import { AiMessageBubble, DEFAULT_WELCOME } from '../components/GroceryGptWidget.jsx'
import { errMsg } from '../utils'

export default function GroceryGptPage() {
  const { user } = useAuth()
  const nav = useNavigate()
  const toast = useToast()

  const [welcome, setWelcome] = useState(DEFAULT_WELCOME)
  const [providerLabel, setProviderLabel] = useState('GROCERYGPT_GROUNDED_ENGINE')
  const [categories, setCategories] = useState([])
  const [selectedCategory, setSelectedCategory] = useState(0)
  const [conversations, setConversations] = useState([])
  const [conversationId, setConversationId] = useState(null)
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)

  const bottomRef = useRef(null)

  const loadSuggestionsAndHistory = () => {
    api.get('/ai/suggestions').then((r) => {
      if (r.data?.welcomeMessage) setWelcome(r.data.welcomeMessage)
      if (r.data?.activeProvider) setProviderLabel(r.data.activeProvider)
      setCategories(r.data?.categories || [])
    }).catch(() => {})
    api.get('/ai/conversations').then((r) => setConversations(r.data || [])).catch(() => {})
  }

  useEffect(() => {
    loadSuggestionsAndHistory()
  }, [])

  useEffect(() => {
    setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }), 60)
  }, [messages, busy])

  const selectConversation = async (id) => {
    try {
      const r = await api.get(`/ai/conversations/${id}`)
      setConversationId(r.data.id)
      setMessages(r.data.messages || [])
    } catch (e) {
      toast(errMsg(e), 'error')
    }
  }

  const startNewChat = () => {
    setConversationId(null)
    setMessages([])
  }

  const deleteConv = async (id, e) => {
    e.stopPropagation()
    try {
      await api.delete(`/ai/conversations/${id}`)
      if (conversationId === id) {
        setConversationId(null)
        setMessages([])
      }
      loadSuggestionsAndHistory()
      toast('Conversation deleted')
    } catch (err) {
      toast(errMsg(err), 'error')
    }
  }

  const clearAll = async () => {
    try {
      await api.delete('/ai/conversations')
      setConversationId(null)
      setMessages([])
      loadSuggestionsAndHistory()
      toast('All conversations cleared')
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
        currentPage: '/grocery-gpt'
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
      loadSuggestionsAndHistory()
    } catch (e) {
      setMessages((prev) => [
        ...prev,
        {
          id: Date.now() + 2,
          role: 'ASSISTANT',
          content: `**Unable to complete request**: ${errMsg(e)}. Please try again.`
        }
      ])
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-4">
      {/* Page Banner */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-gradient-to-r from-slate-900 via-emerald-950 to-slate-900 p-4 text-white shadow-sm sm:p-5">
        <div className="flex items-center gap-3.5">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-500/20 text-emerald-400 ring-1 ring-emerald-400/30">
            <Bot className="h-7 w-7" />
          </div>
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <h1 className="text-lg font-bold sm:text-xl">GroceryGPT — Intelligent Business Assistant</h1>
              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/20 px-2.5 py-0.5 text-xs font-semibold text-emerald-300">
                <Database className="h-3 w-3" /> Live PostgreSQL Connected
              </span>
              <span className="inline-flex items-center gap-1 rounded-full bg-slate-800 px-2.5 py-0.5 text-xs font-medium text-slate-300">
                <ShieldCheck className="h-3 w-3 text-emerald-400" /> Role: {user?.role}
              </span>
            </div>
            <p className="mt-0.5 text-xs text-slate-300 sm:text-sm">
              Real-time Sales Analytics, Inventory & Reorder, Accounting & P&L, HR Attendance, Payroll & Step-by-Step Application Guidance
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="secondary" onClick={startNewChat}>
            <MessageSquarePlus className="h-4 w-4" /> New Chat
          </Button>
          {conversations.length > 0 && (
            <Button variant="ghost" className="text-slate-300 hover:bg-white/10 hover:text-white" onClick={clearAll}>
              <Trash2 className="h-4 w-4" /> Clear History
            </Button>
          )}
        </div>
      </div>

      {/* Main Split Workspace */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
        {/* Left Sidebar: Prompt Library & Conversation History */}
        <div className="space-y-4 lg:col-span-4 xl:col-span-3">
          {/* Categorized Prompt Explorer */}
          <div className="card p-4">
            <div className="mb-3 flex items-center gap-2">
              <BookOpen className="h-4 w-4 text-emerald-600" />
              <h2 className="text-sm font-bold text-slate-800">Business & App Questions</h2>
            </div>
            {categories.length > 0 && (
              <>
                <div className="mb-3 flex flex-wrap gap-1">
                  {categories.map((cat, idx) => (
                    <button
                      key={idx}
                      onClick={() => setSelectedCategory(idx)}
                      className={cn(
                        'rounded-lg px-2.5 py-1 text-xs font-medium transition',
                        selectedCategory === idx
                          ? 'bg-emerald-600 text-white'
                          : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                      )}
                    >
                      {cat.category}
                    </button>
                  ))}
                </div>
                <div className="max-h-64 space-y-1.5 overflow-y-auto pr-1">
                  {(categories[selectedCategory]?.questions || []).map((q, qIdx) => (
                    <button
                      key={qIdx}
                      onClick={() => sendMessage(q)}
                      disabled={busy}
                      className="flex w-full items-center justify-between rounded-lg border border-slate-200/80 bg-slate-50/70 px-3 py-2 text-left text-xs font-medium text-slate-700 transition hover:border-emerald-300 hover:bg-emerald-50 hover:text-emerald-800 disabled:opacity-50"
                    >
                      <span>{q}</span>
                      <Sparkles className="h-3.5 w-3.5 shrink-0 text-emerald-500" />
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>

          {/* Saved Conversation History */}
          <div className="card p-4">
            <div className="mb-2.5 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <MessageSquare className="h-4 w-4 text-emerald-600" />
                <h2 className="text-sm font-bold text-slate-800">Conversation History</h2>
              </div>
              <span className="text-xs text-slate-400">{conversations.length} saved</span>
            </div>
            {conversations.length === 0 ? (
              <p className="py-4 text-center text-xs text-slate-400">
                Your conversations with GroceryGPT will be saved here automatically.
              </p>
            ) : (
              <div className="max-h-64 space-y-1 overflow-y-auto pr-1">
                {conversations.map((c) => (
                  <div
                    key={c.id}
                    onClick={() => selectConversation(c.id)}
                    className={cn(
                      'group flex cursor-pointer items-center justify-between rounded-lg px-2.5 py-2 text-xs transition',
                      conversationId === c.id
                        ? 'bg-emerald-50 font-semibold text-emerald-800 ring-1 ring-emerald-200'
                        : 'text-slate-700 hover:bg-slate-100'
                    )}
                  >
                    <div className="min-w-0 flex-1 pr-2">
                      <p className="truncate">{c.title}</p>
                      {c.lastIntent && (
                        <p className="text-[10px] font-normal text-slate-400">{c.lastIntent}</p>
                      )}
                    </div>
                    <button
                      onClick={(e) => deleteConv(c.id, e)}
                      className="rounded p-1 text-slate-400 opacity-0 hover:bg-rose-50 hover:text-rose-600 group-hover:opacity-100"
                      title="Delete conversation"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Right Main Chat Panel */}
        <div className="flex h-[calc(100vh-14rem)] min-h-[540px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-slate-50 shadow-sm lg:col-span-8 xl:col-span-9">
          {/* Chat Top Status Bar */}
          <div className="flex items-center justify-between border-b border-slate-200 bg-white px-4 py-2.5 text-xs text-slate-500">
            <span>
              {conversationId ? `Active Conversation #${conversationId}` : 'New Session — Grounded in live PostgreSQL records'}
            </span>
            <span className="font-mono text-[11px] text-slate-400">Engine: {providerLabel}</span>
          </div>

          {/* Chat Message Stream */}
          <div className="flex-1 space-y-4 overflow-y-auto p-4 sm:p-5">
            {/* Official Welcome Message */}
            <AiMessageBubble
              msg={{
                id: 'welcome-main',
                role: 'ASSISTANT',
                content: welcome
              }}
              onAskFollowUp={(q) => sendMessage(q)}
              onNavigate={(path) => nav(path)}
            />

            {messages.map((m) => (
              <AiMessageBubble
                key={m.id}
                msg={m}
                onAskFollowUp={(q) => sendMessage(q)}
                onNavigate={(path) => nav(path)}
              />
            ))}

            {busy && (
              <div className="flex items-center gap-2.5">
                <div className="flex h-7 w-7 items-center justify-center rounded-full bg-emerald-600 text-white">
                  <Bot className="h-4 w-4 animate-pulse" />
                </div>
                <div className="flex items-center gap-1.5 rounded-2xl rounded-tl-none border border-slate-200 bg-white px-4 py-3 text-xs text-slate-500 shadow-sm">
                  <span className="h-2 w-2 animate-bounce rounded-full bg-emerald-500 [animation-delay:-0.3s]" />
                  <span className="h-2 w-2 animate-bounce rounded-full bg-emerald-500 [animation-delay:-0.15s]" />
                  <span className="h-2 w-2 animate-bounce rounded-full bg-emerald-500" />
                  <span className="ml-1.5 font-medium text-slate-600">
                    GroceryGPT is querying live store records & preparing your answer...
                  </span>
                </div>
              </div>
            )}
            <div ref={bottomRef} />
          </div>

          {/* Chat Input Bar */}
          <form
            onSubmit={(e) => { e.preventDefault(); sendMessage() }}
            className="border-t border-slate-200 bg-white p-3.5"
          >
            <div className="flex items-center gap-2.5">
              <input
                type="text"
                className="input flex-1 text-sm"
                placeholder="Ask anything about sales, products, inventory, attendance, payroll, accounting, or how to use the app..."
                value={input}
                onChange={(e) => setInput(e.target.value)}
                disabled={busy}
              />
              <Button type="submit" loading={busy} disabled={!input.trim()}>
                <Send className="h-4 w-4" /> Ask GroceryGPT
              </Button>
            </div>
          </form>
        </div>
      </div>
    </div>
  )
}
