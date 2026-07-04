import { definePlugin } from '@halo-dev/ui-shared'
import { IconSettings, VLoading } from '@halo-dev/components'
import { defineAsyncComponent, markRaw } from 'vue'
import RiBook2Line from '~icons/ri/book-2-line'

const DocDashboardWidget = defineAsyncComponent({
  loader: () => import('./components/DocDashboardWidget.vue'),
  loadingComponent: VLoading,
})

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/docs',
        name: 'DocLibraries',
        component: defineAsyncComponent({
          loader: () => import('./views/DocLibraryList.vue'),
          loadingComponent: VLoading,
        }),
        meta: {
          title: '文档',
          searchable: true,
          permissions: ['plugin:my-docs:libraries:view'],
          menu: {
            name: '文档',
            group: 'content',
            icon: markRaw(RiBook2Line),
            priority: 40,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/docs/settings',
        name: 'DocSettings',
        component: defineAsyncComponent({
          loader: () => import('./views/DocSettings.vue'),
          loadingComponent: VLoading,
        }),
        meta: {
          title: '文档设置',
          searchable: true,
          permissions: ['plugin:my-docs:libraries:manage'],
          menu: {
            name: '文档设置',
            group: 'content',
            icon: markRaw(IconSettings),
            priority: 41,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/docs/libraries/:libraryName',
        name: 'DocList',
        component: defineAsyncComponent({
          loader: () => import('./views/DocList.vue'),
          loadingComponent: VLoading,
        }),
        meta: {
          title: '文档管理',
          permissions: ['plugin:my-docs:libraries:view'],
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/docs/libraries/:libraryName/editor',
        name: 'DocEditor',
        component: defineAsyncComponent({
          loader: () => import('./views/DocEditor.vue'),
          loadingComponent: VLoading,
        }),
        meta: {
          title: '文档编辑',
          permissions: ['plugin:my-docs:libraries:manage'],
        },
      },
    },
  ],
  extensionPoints: {
    'console:dashboard:widgets:create': () => [
      {
        id: 'my-docs-dashboard-widget',
        name: '文档',
        component: markRaw(DocDashboardWidget),
        group: 'content',
        defaultSize: {
          w: 4,
          h: 2,
          minW: 3,
          minH: 2,
        },
        priority: 40,
        permissions: ['plugin:my-docs:libraries:view'],
      },
    ],
  },
})
