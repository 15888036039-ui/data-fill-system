import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      redirect: (to) => {
        const userParam = to.query.user || ''
        let user = ''
        if (userParam) {
          user = isEncrypted(userParam) ? decryptUser(userParam) : userParam
        }
        
        if (user === 'finereport_manage') {
          return { path: '/forms', query: to.query }
        }
        return { path: '/tasks', query: to.query }
      }
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('../views/UserTasks.vue')
    },
    {
      path: '/forms',
      name: 'forms',
      component: () => import('../views/FormList.vue')
    },
    {
      path: '/designer',
      name: 'designer',
      component: () => import('../views/FormDesigner.vue')
    },
    {
      path: '/designer/:id',
      name: 'designerEdit',
      component: () => import('../views/FormDesigner.vue')
    },
    {
      path: '/fill/:id',
      name: 'fill',
      component: () => import('../views/DataFill.vue')
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('../views/SystemSettings.vue')
    }
  ]
})

import { decryptUser, isEncrypted } from '../utils/crypto.js'

router.beforeEach((to, from, next) => {
  const userParam = to.query.user || from.query.user || ''
  
  // 识别身份
  let user = ''
  if (userParam) {
    user = isEncrypted(userParam) ? decryptUser(userParam) : userParam
  }
  const isAdmin = user === 'finereport_manage'

  // 管理员强制进入模板管理，禁止进入填报工作台
  if (isAdmin && to.path === '/tasks') {
    return next({ path: '/forms', query: { ...to.query, user: userParam } })
  }

  // 普通用户禁止进入模板管理，强制回到填报工作台
  if (!isAdmin && to.path === '/forms') {
    return next({ path: '/tasks', query: { ...to.query, user: userParam } })
  }

  // Preserve the 'user' query parameter across navigations
  if (from.query.user && !to.query.user) {
    next({
      name: to.name,
      params: to.params,
      query: { ...to.query, user: from.query.user }
    })
  } else {
    next()
  }
})

export default router
