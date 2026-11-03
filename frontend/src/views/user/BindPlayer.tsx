import React, { useState } from 'react'
import { hot } from 'react-hot-loader/root'
import useBlessingExtra from '@/scripts/hooks/useBlessingExtra'
import useEmitMounted from '@/scripts/hooks/useEmitMounted'
import { post, ResponseBody } from '@/scripts/net'
import { Player } from '@/scripts/types'
import Alert from '@/components/Alert'

const BindPlayer: React.FC = () => {
  const players = useBlessingExtra<Player[]>('players') ?? []
  const min = useBlessingExtra<number>('min')
  const max = useBlessingExtra<number>('max')
  const [name, setName] = useState('')
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState('')
  useEmitMounted()

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setPending(true)
    try {
      const response = await post<ResponseBody>('/user/player/bind', { player: name })
      if (response.code === 0) window.location.href = `${blessing.base_url}/user`
      else setMessage(response.message)
    } finally {
      setPending(false)
    }
  }

  return <form onSubmit={submit}>
    <p>本站每个账号使用一个角色，昵称会与角色名同步。</p>
    {players.length > 1 && <div className="alert alert-warning">
      你的账号已有多个角色。请选择要保留的角色，或输入一个新角色名。确认后其他角色将被删除，已上传的皮肤和衣柜收藏会保留。
    </div>}
    <div className="form-group"><label htmlFor="bind-player-name">角色名</label>
      <input id="bind-player-name" className="form-control" list="existing-players" required
        minLength={min} maxLength={max} value={name} onChange={event => setName(event.target.value)} />
      <datalist id="existing-players">{players.map(player => <option key={player.pid} value={player.name} />)}</datalist>
    </div>
    {players.length > 1 && <label className="mb-3"><input type="checkbox" required /> 我确认只保留本次绑定的角色</label>}
    <Alert type="warning">{message}</Alert>
    <button className="btn btn-primary btn-block" type="submit" disabled={pending}>{pending ? '正在绑定…' : '确认绑定'}</button>
  </form>
}

export default hot(BindPlayer)
