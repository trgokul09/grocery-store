import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth.jsx'
import { Spinner } from './components/ui.jsx'
import Layout from './components/Layout.jsx'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import POS from './pages/POS.jsx'
import Products from './pages/Products.jsx'
import Inventory from './pages/Inventory.jsx'
import Suppliers from './pages/Suppliers.jsx'
import PurchaseOrders from './pages/PurchaseOrders.jsx'
import Reorder from './pages/Reorder.jsx'
import Expenses from './pages/Expenses.jsx'
import Shifts from './pages/Shifts.jsx'
import Sales from './pages/Sales.jsx'
import Users from './pages/Users.jsx'
import AuditLogs from './pages/AuditLogs.jsx'
import Notifications from './pages/Notifications.jsx'

function Guard({ roles, children }) {
  const { user, can } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  if (roles && !can(...roles)) return <Navigate to="/" replace />
  return children
}

const MGMT = ['OWNER', 'MANAGER']

export default function App() {
  const { user, loading } = useAuth()
  if (loading) return <Spinner className="py-40" />
  const home = user ? (MGMT.includes(user.role) ? '/dashboard' : '/pos') : '/login'
  return (
    <Routes>
      <Route path="/login" element={user ? <Navigate to={home} replace /> : <Login />} />
      <Route element={<Guard><Layout /></Guard>}>
        <Route path="/dashboard" element={<Guard roles={MGMT}><Dashboard /></Guard>} />
        <Route path="/pos" element={<POS />} />
        <Route path="/sales" element={<Sales />} />
        <Route path="/products" element={<Products />} />
        <Route path="/inventory" element={<Guard roles={MGMT}><Inventory /></Guard>} />
        <Route path="/purchase-orders" element={<PurchaseOrders />} />
        <Route path="/suppliers" element={<Guard roles={MGMT}><Suppliers /></Guard>} />
        <Route path="/reorder" element={<Guard roles={MGMT}><Reorder /></Guard>} />
        <Route path="/expenses" element={<Expenses />} />
        <Route path="/shifts" element={<Shifts />} />
        <Route path="/notifications" element={<Guard roles={MGMT}><Notifications /></Guard>} />
        <Route path="/users" element={<Guard roles={['OWNER']}><Users /></Guard>} />
        <Route path="/audit" element={<Guard roles={['OWNER']}><AuditLogs /></Guard>} />
      </Route>
      <Route path="*" element={<Navigate to={home} replace />} />
    </Routes>
  )
}
