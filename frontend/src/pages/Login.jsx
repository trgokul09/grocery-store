import { useState } from 'react'
import { Boxes } from 'lucide-react'
import { useAuth } from '../auth.jsx'
import { Button, Field } from '../components/ui.jsx'
import { errMsg } from '../utils'

export default function Login() {
  const { login } = useAuth()
  const [username, setU] = useState('')
  const [password, setP] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true); setErr('')
    try { await login(username.trim(), password) } catch (x) { setErr(errMsg(x)) } finally { setBusy(false) }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-900 via-slate-800 to-emerald-900 p-4">
      <form onSubmit={submit} className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-2xl">
        <div className="mb-5 flex flex-col items-center gap-2">
          <span className="rounded-2xl bg-emerald-100 p-3"><Boxes className="h-8 w-8 text-emerald-600" /></span>
          <h1 className="text-xl font-bold text-slate-900">Smart Grocery</h1>
          <p className="text-sm text-slate-500">Sign in to manage your store</p>
        </div>
        <div className="space-y-3">
          <Field label="Username"><input className="input" value={username} onChange={(e) => setU(e.target.value)} autoFocus autoCapitalize="none" autoComplete="username" required /></Field>
          <Field label="Password"><input className="input" type="password" value={password} onChange={(e) => setP(e.target.value)} autoComplete="current-password" required /></Field>
          {err && <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{err}</p>}
          <Button type="submit" className="w-full" size="lg" loading={busy}>Sign in</Button>
        </div>
      </form>
    </div>
  )
}
