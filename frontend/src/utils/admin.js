export const ADMIN_USERS = ['finereport_manage', '15888036039@163.com', '15888036039']

export const isKnownAdmin = (user) => {
  if (!user) return false
  return ADMIN_USERS.includes(user)
}
