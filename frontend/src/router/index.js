import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      redirect: '/forms'
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

export default router
