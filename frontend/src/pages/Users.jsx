import { useCallback, useEffect, useState } from 'react'
import { KeyRound, Pencil, Plus, Power } from 'lucide-react'
import api from '../api'
import { Badge, Button, Card, Field, Modal, PageHeader, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { errMsg, fmtDate } from '../utils'

const ROLES = ['OWNER', 'MANAGER', 'STAFF']
const TONE = { OWNER: 'indigo', MANAGER: 'amber', STAFF: 'emerald' }

export default function Users() {
  const toast = useToast()
  const [list, setList] = useState(null)
  const [form, setForm] = useState(null)
  const load = useCallback(() => api.get('/users').then((r) => setList(r.data)).catch((e) => toast(errMsg(e), 'error')), []) // eslint-disable-line
  useEffect(() => { load() }, [load])

  const save = async () => {
    try {
      if (form.id) await api.put(`/users/${form.id}`, { fullName: form.fullName, role: form.role })
      else await api.post('/users', { username: form.username, password: form.password, fullName: form.fullName, role: form.role })
      toast('User saved'); setForm(null); load()
    } catch (e) { toast(errMsg(e), 'error') }
  }
  const toggle = async (u) => { try { await api.patch(`/users/${u.id}/active`, null, { params: { value: !u.active } }); load() } catch (e) { toast(errMsg(e), 'error') } }
  const reset = async (u) => {
    const pw = window.prompt(`New password for ${u.username} (min 8 characters)`)
    if (!pw) return
    try { await api.post(`/users/${u.id}/reset-password`, { newPassword: pw }); toast('Password reset') } catch (e) { toast(errMsg(e), 'error') }
  }

  return (
    <div>
      <PageHeader title="Staff management" subtitle="Create logins and assign roles"><Button size="sm" onClick={() => setForm({ username: '', password: '', fullName: '', role: 'STAFF' })}><Plus className="h-4 w-4" />Add user</Button></PageHeader>
      <Card>
        {!list ? <Spinner /> : (
          <TableWrap>
            <thead><tr><th className="th">Name</th><th className="th">Username</th><th className="th">Role</th><th className="th">Since</th><th className="th">Status</th><th className="th" /></tr></thead>
            <tbody className="divide-y divide-slate-100">{list.map((u) => <tr key={u.id}><td className="td font-medium">{u.fullName}</td><td className="td text-slate-500">{u.username}</td><td className="td"><Badge tone={TONE[u.role]}>{u.role}</Badge></td><td className="td">{fmtDate(u.createdAt)}</td><td className="td">{u.active ? <Badge tone="emerald">Active</Badge> : <Badge>Disabled</Badge>}</td>
              <td className="td"><div className="flex gap-1"><Button variant="ghost" size="sm" onClick={() => setForm({ ...u })}><Pencil className="h-4 w-4" /></Button><Button variant="ghost" size="sm" onClick={() => reset(u)}><KeyRound className="h-4 w-4" /></Button><Button variant="ghost" size="sm" onClick={() => toggle(u)}><Power className="h-4 w-4" /></Button></div></td></tr>)}</tbody>
          </TableWrap>
        )}
      </Card>
      <Modal open={!!form} onClose={() => setForm(null)} title={form?.id ? 'Edit user' : 'Add user'} footer={<><Button variant="secondary" onClick={() => setForm(null)}>Cancel</Button><Button onClick={save}>Save</Button></>}>
        {form && (
          <div className="space-y-3">
            <Field label="Full name"><input className="input" value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} /></Field>
            {!form.id && <><Field label="Username" hint="Letters, digits, . _ -"><input className="input" autoCapitalize="none" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} /></Field><Field label="Temporary password" hint="At least 8 characters"><input className="input" type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></Field></>}
            <Field label="Role"><select className="input" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select></Field>
          </div>
        )}
      </Modal>
    </div>
  )
}
