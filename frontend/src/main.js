import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import axios from 'axios'

// 统一为 Axios 请求的 API 地址加上 /datafill 前缀
axios.interceptors.request.use(config => {
  if (config.url && config.url.startsWith('/api')) {
    config.url = '/datafill' + config.url
  }
  return config
})

const app = createApp(App)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.use(router)
app.use(ElementPlus, {
  locale: zhCn,
})
app.mount('#app')
