import React, { useEffect, useState } from 'react'
import { hot } from 'react-hot-loader/root'
import * as fetch from '@/scripts/net'
import { toast } from '@/scripts/notify'
import Loading from '@/components/Loading'

interface Extension {
  name: string
  title: string
  description: string
  enabled: boolean
  configUrl: string
}

const OfficialExtensions: React.FC = () => {
  const [extensions, setExtensions] = useState<Extension[]>([])
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState('')

  useEffect(() => {
    fetch.get<Extension[]>('/admin/extensions/data').then(setExtensions).finally(() => setLoading(false))
  }, [])

  const toggle = async (extension: Extension) => {
    setPending(extension.name)
    try {
      const { code, message } = await fetch.put<fetch.ResponseBody>(
        `/admin/extensions/${extension.name}`, { enabled: !extension.enabled },
      )
      if (code === 0) {
        setExtensions((current) => current.map((item) => item.name === extension.name ? { ...item, enabled: !item.enabled } : item))
        toast.success(message)
      } else toast.error(message)
    } finally { setPending('') }
  }

  if (loading) return <Loading />
  return <div className="row">
    {extensions.map((extension) => <div className="col-md-6 col-xl-4" key={extension.name}>
      <div className="card">
        <div className="card-header"><h2 className="card-title">{extension.title}</h2></div>
        <div className="card-body"><p>{extension.description}</p>
          <label><input type="checkbox" className="mr-2" checked={extension.enabled}
            disabled={pending !== ''} onChange={() => toggle(extension)} />启用</label>
        </div>
        {extension.configUrl && <div className="card-footer"><a className="btn btn-default" href={extension.configUrl}>配置</a></div>}
      </div>
    </div>)}
  </div>
}

export default hot(OfficialExtensions)
