import request from './request'

export const login = (data) => request.post('/login', data)
export const getStats = () => request.get('/stats')
export const getSessions = () => request.get('/sessions')
export const getSession = (id) => request.get('/session', { params: { id } })
export const getLogs = () => request.get('/logs')
export const setMode = (mode) => request.post('/mode', { mode })
export const sendCommand = (payload) => request.post('/command', payload)
export const kickDevice = (device_id) => request.post('/kick', { device_id })
