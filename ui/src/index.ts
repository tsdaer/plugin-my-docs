import { definePlugin } from '@halo-dev/ui-shared'
import { VLoading } from '@halo-dev/components'
import { defineAsyncComponent, markRaw } from 'vue'
import RiBook2Line from '~icons/ri/book-2-line'

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
  ],
  extensionPoints: {},
})
