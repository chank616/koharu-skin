import React, { useEffect, useState } from 'react'
import * as fetch from '@/scripts/net'
import { showModal, toast } from '@/scripts/notify'
import Loading from '@/components/Loading'

type Grant = {
  id: string
  scopes: string[]
  created_at: string | null
  expires_at: string
  client: { id: number; name: string; redirect: string }
}

const AuthorizedApps: React.FC = () => {
  const [grants, setGrants] = useState<Grant[]>([])
  const [descriptions, setDescriptions] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    Promise.all([
      fetch.get<Grant[]>('/oauth/tokens'),
      fetch.get<Array<{ id: string; description: string }>>('/oauth/scopes'),
    ]).then(([grants, scopes]) => {
      setGrants(grants)
      setDescriptions(Object.fromEntries(scopes.map((s) => [s.id, s.description])))
      setLoading(false)
    })
  }, [])

  const revoke = async (grant: Grant) => {
    try {
      await showModal({ text: `撤销“${grant.client.name}”的这次授权？`, okButtonType: 'danger' })
    } catch {
      return
    }
    const result = await fetch.del<unknown>(`/oauth/tokens/${grant.id}`)
    if (result === '') {
      setGrants((items) => items.filter((item) => item.id !== grant.id))
      toast.success('已撤销此次授权')
    }
  }

  return (
    <section className="mt-4">
      <h2 className="h4">已授权的应用</h2>
      <p>每条记录对应一次授权，可以单独撤销。</p>
      <div className="card"><div className="card-body table-responsive p-0">
        <table className="table table-striped">
          <thead><tr><th>应用</th><th>允许访问</th><th>授权时间</th><th>操作</th></tr></thead>
          <tbody>
            {grants.map((grant) => (
              <tr key={grant.id}>
                <td>{grant.client.name}</td>
                <td>{grant.scopes.map((scope) => descriptions[scope] || scope).join('；')}</td>
                <td>{grant.created_at ? new Date(grant.created_at).toLocaleString() : '未知'}</td>
                <td><button className="btn btn-danger" onClick={() => revoke(grant)}>撤销此次授权</button></td>
              </tr>
            ))}
            {grants.length === 0 && <tr><td colSpan={4} className="text-center">{loading ? <Loading /> : '暂无授权记录'}</td></tr>}
          </tbody>
        </table>
      </div></div>
    </section>
  )
}

export default AuthorizedApps
