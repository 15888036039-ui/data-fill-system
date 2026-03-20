const PREFIX = 'u_'

export const encryptUser = (userStr) => {
  if (!userStr) return ''
  // If already encrypted, don't double encrypt
  if (userStr.startsWith(PREFIX)) return userStr
  try {
    return PREFIX + btoa(encodeURIComponent(userStr))
  } catch (e) {
    console.error('Encryption failed:', e)
    return userStr
  }
}

export const decryptUser = (str) => {
  if (!str) return ''
  if (!str.startsWith(PREFIX)) return str // Treat as plain text
  try {
    return decodeURIComponent(atob(str.slice(PREFIX.length)))
  } catch (e) {
    console.error('Decryption failed:', e)
    return str
  }
}

export const isEncrypted = (str) => {
  return str && str.startsWith(PREFIX)
}
