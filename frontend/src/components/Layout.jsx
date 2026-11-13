import { useEffect, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { Bell, Boxes, ClipboardList, LayoutDashboard, LogOut, Menu, Package, Receipt, ScrollText, ShoppingCart, Timer, Truck, Users, Wallet, RefreshCcw, KeyRound, X, Warehouse } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { Button, Field, Modal, cn, useToast } from './ui.jsx'
import { errMsg } from '../utils'

const NAV = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, roles: ['OWNER', 'MANAGER'] },
  { to: '/pos', label: 'POS Billing', icon: ShoppingCart, roles: ['OWNER', 'MANAGER', 'STAFF'] },
  { to: '/sales', label: 'Sales', icon: Receipt, roles: ['OWNER', 'MANAGER', 'STAFF'] },
  { to: '/products', label: 'Products', icon: Package, roles: ['OWNER', 'MANAGER', 'STAFF'] },
  { to: '/inventory', label: 'Inventory', icon: Warehouse, roles: ['OWNER', 'MANAGER'] },
  { to: '/purchase-orders', label: 'Purchase Orders', icon: ClipboardList, roles: ['OWNER', 'MANAGER', 'STAFF'] },
  { to: '/suppliers', label: 'Suppliers', icon: Truck, roles: ['OWNER', 'MANAGER'] },
  { to: '/reorder', label: 'Smart Reorder', icon: RefreshCcw, roles: ['OWNER', 'MANAGER'] },
  { to: '/expenses', label: 'Expenses', icon: Wallet, roles: ['OWNER', 'MANAGER', 'STAFF'] },
  { to: '/shifts', label: 'Shifts', icon: Timer, roles: ['OWNER', 'MANAGER', 'STAFF'] },
  { to: '/users', label: 'Staff', icon: Users, roles: ['OWNER'] },
  { to: '/audit', label: 'Audit Log', icon: ScrollText, roles: ['OWNER'] },
]

const linkCls = ({ isActive }) => cn('flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium', isActive ? 'bg-emerald-600 text-white' : 'text-slate-300 hover:bg-slate-800 hover:text-white')

function ChangePassword({ open, onClose }) {
  const toast = useToast()
  const [f, setF] = useState({ currentPassword: '', newPassword: '' })
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    setBusy(true)
    try { await api.post('/auth/change-password', f); toast('Password changed'); setF({ currentPassword: '', newPassword: '' }); onClose() }
    catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal open={open} onClose={onClose} title="Change password" footer={<Button onClick={submit} loading={busy}>Update</Button>}>
      <div className="space-y-3">
        <Field label="Current password"><input type="password" className="input" value={f.currentPassword} onChange={(e) => setF({ ...f, currentPassword: e.target.value })} /></Field>
        <Field label="New password" hint="At least 8 characters"><input type="password" className="input" value={f.newPassword} onChange={(e) => setF({ ...f, newPassword: e.target.value })} /></Field>
      </div>
    </Modal>
  )
}

export default function Layout() {
  const { user, logout, can } = useAuth()
  const nav = useNavigate()
  const [drawer, setDrawer] = useState(false)
  const [unread, setUnread] = useState(0)
  const [pwd, setPwd] = useState(false)
  const toast = useToast()
  const items = NAV.filter((n) => n.roles.includes(user.role))

  // Staff can only sign out after closing their shift (enforced by the server too)
  const signOut = async () => {
    try { await api.post('/auth/logout') } catch (e) { toast(errMsg(e), 'error'); setDrawer(false); nav('/shifts'); return }
    logout(); nav('/login')
  }
  const bottom = items.slice(0, 4)

  // Near-real-time alerts: poll the unread counter (owner/manager only)
  useEffect(() => {
    if (!can('OWNER', 'MANAGER')) return
    const load = () => api.get('/notifications/unread-count').then((r) => setUnread(r.data.count)).catch(() => {})
    load()
    const t = setInterval(load, 30000)
    return () => clearInterval(t)
  }, [user.role]) // eslint-disable-line

  const sidebar = (
    <div className="flex h-full flex-col bg-slate-900 p-3">
      <div className="mb-4 flex items-center justify-between px-2 pt-1">
        <div className="flex items-center gap-2 text-white"><Boxes className="h-6 w-6 text-emerald-400" /><span className="text-lg font-bold">Smart Grocery</span></div>
        <button className="text-slate-400 lg:hidden" onClick={() => setDrawer(false)}><X className="h-5 w-5" /></button>
      </div>
      <nav className="flex-1 space-y-1 overflow-y-auto">
        {items.map((n) => <NavLink key={n.to} to={n.to} className={linkCls} onClick={() => setDrawer(false)}><n.icon className="h-4 w-4" />{n.label}</NavLink>)}
      </nav>
      <div className="mt-3 border-t border-slate-700 pt-3">
        <p className="px-2 text-sm font-medium text-white">{user.fullName}</p>
        <p className="mb-2 px-2 text-xs text-slate-400">{user.role}</p>
        <button onClick={() => setPwd(true)} className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm text-slate-300 hover:bg-slate-800"><KeyRound className="h-4 w-4" />Change password</button>
        <button onClick={signOut} className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm text-slate-300 hover:bg-slate-800"><LogOut className="h-4 w-4" />Sign out</button>
      </div>
    </div>
  )

  return (
    <div className="min-h-screen lg:pl-60">
      <aside className="fixed inset-y-0 left-0 hidden w-60 lg:block">{sidebar}</aside>
      {drawer && (
        <div className="fixed inset-0 z-40 lg:hidden"><div className="absolute inset-0 bg-slate-900/60" onClick={() => setDrawer(false)} /><div className="absolute inset-y-0 left-0 w-64">{sidebar}</div></div>
      )}
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-slate-200 bg-white/90 px-3 py-2.5 backdrop-blur lg:px-6">
        <button className="rounded-lg p-1.5 text-slate-600 hover:bg-slate-100 lg:hidden" onClick={() => setDrawer(true)}><Menu className="h-6 w-6" /></button>
        <span className="font-semibold text-slate-800 lg:hidden">Smart Grocery</span>
        <span className="hidden text-sm text-slate-500 lg:block">Welcome back, {user.fullName}</span>
        <div className="flex items-center gap-1">
          {can('OWNER', 'MANAGER') && (
            <button className="relative rounded-lg p-2 text-slate-600 hover:bg-slate-100" onClick={() => nav('/notifications')}>
              <Bell className="h-5 w-5" />
              {unread > 0 && <span className="absolute right-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-bold text-white">{unread > 99 ? '99+' : unread}</span>}
            </button>
          )}
        </div>
      </header>
      <main className="p-3 pb-24 sm:p-4 lg:p-6 lg:pb-6"><Outlet /></main>
      <nav className="fixed inset-x-0 bottom-0 z-30 flex border-t border-slate-200 bg-white pb-[env(safe-area-inset-bottom)] lg:hidden">
        {bottom.map((n) => (
          <NavLink key={n.to} to={n.to} className={({ isActive }) => cn('flex flex-1 flex-col items-center gap-0.5 py-2 text-[11px] font-medium', isActive ? 'text-emerald-600' : 'text-slate-500')}><n.icon className="h-5 w-5" />{n.label.split(' ')[0]}</NavLink>
        ))}
        <button className="flex flex-1 flex-col items-center gap-0.5 py-2 text-[11px] font-medium text-slate-500" onClick={() => setDrawer(true)}><Menu className="h-5 w-5" />More</button>
      </nav>
      <ChangePassword open={pwd} onClose={() => setPwd(false)} />
    </div>
  )
}
