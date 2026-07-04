<script setup lang="ts">
import {
  VButton,
  VCard,
  VPageHeader,
  VEmpty,
  VLoading,
  VStatusDot,
  VDropdown,
  VDropdownItem,
  Dialog,
  Toast,
  IconAddCircle,
  IconMore,
} from '@halo-dev/components'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { axiosInstance } from '@halo-dev/api-client'
import { Draggable as DraggableTree } from '@he-tree/vue'
import '@he-tree/vue/style/default.css'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import { DocV1alpha1Api, DocLibraryV1alpha1Api } from '@/api/generated'
import type { Doc } from '@/api/generated'
import { buildDocTree, flattenPositions, type DocTreeNode } from '@/utils/doc-tree'

const DOC_ENDPOINT = '/apis/console.api.docs.halo.run/v1alpha1/docs'
// 树需要全量节点才能组装，暂不分页；超出上限记录并提示。
const MAX_DOCS = 200

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

const docApi = new DocV1alpha1Api(undefined, '', axiosInstance)
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)

const libraryName = computed(() => route.params.libraryName as string)

// he-tree 直接绑定的树数据；由查询结果组装，拖拽会原地修改它。
const treeData = ref<DocTreeNode[]>([])
// 以 name 为键的原始文档快照，用于拖拽后对比 parent/priority 是否变化。
const docByName = ref<Map<string, Doc>>(new Map())
const saving = ref(false)

const { data: library } = useQuery({
  queryKey: ['doc-library', libraryName],
  queryFn: async () => {
    const { data } = await libraryApi.getDocLibrary({ name: libraryName.value })
    return data
  },
})

const { data, isLoading } = useQuery({
  queryKey: ['docs', libraryName],
  queryFn: async () => {
    const { data } = await docApi.listDoc({
      page: 1,
      size: MAX_DOCS,
      fieldSelector: [`spec.libraryName=${libraryName.value}`],
      sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    if (data.total > MAX_DOCS) {
      console.warn(
        `[my-docs] 文档数量 ${data.total} 超过树形展示上限 ${MAX_DOCS}，仅展示前 ${MAX_DOCS} 篇。`,
      )
      Toast.warning(`文档过多，仅展示前 ${MAX_DOCS} 篇`)
    }
    return data
  },
})

watch(
  data,
  (value) => {
    const items = value?.items ?? []
    docByName.value = new Map(items.map((doc) => [doc.metadata.name, doc]))
    treeData.value = buildDocTree(items)
  },
  { immediate: true },
)

const isEmpty = computed(() => !isLoading.value && treeData.value.length === 0)

function handleBack() {
  router.push({ name: 'DocLibraries' })
}

function handleCreate(parent?: string) {
  router.push({
    name: 'DocEditor',
    params: { libraryName: libraryName.value },
    query: parent ? { parent } : undefined,
  })
}

function handleEdit(doc: Doc) {
  router.push({
    name: 'DocEditor',
    params: { libraryName: libraryName.value },
    query: { name: doc.metadata.name },
  })
}

function handleDelete(doc: Doc) {
  Dialog.warning({
    title: '确定要删除该文档吗？',
    description: `文档「${doc.spec.title}」将被删除，其子文档会上浮为顶层。该操作不可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      await docApi.deleteDoc({ name: doc.metadata.name })
      Toast.success('删除成功')
      await queryClient.invalidateQueries({ queryKey: ['docs'] })
    },
  })
}

// 拖拽完成：对比新树的期望位置与原值，仅对 parent/priority 变化的节点即时保存。
async function handleDrop() {
  const positions = flattenPositions(treeData.value)
  const changed = positions.filter((pos) => {
    const doc = docByName.value.get(pos.name)
    if (!doc) {
      return false
    }
    const curParent = doc.spec.parent ?? ''
    const curPriority = doc.spec.priority ?? 0
    return curParent !== pos.parent || curPriority !== pos.priority
  })

  if (changed.length === 0) {
    return
  }

  saving.value = true
  try {
    await Promise.all(
      changed.map((pos) => {
        const doc = docByName.value.get(pos.name)!
        const updated: Doc = {
          ...doc,
          spec: {
            ...doc.spec,
            parent: pos.parent || undefined,
            priority: pos.priority,
          },
        }
        return axiosInstance.put(`${DOC_ENDPOINT}/${pos.name}`, updated)
      }),
    )
    Toast.success('已保存文档结构')
  } finally {
    saving.value = false
    // 无论成败都重拉，成功校正 version，失败回滚视图。
    await queryClient.invalidateQueries({ queryKey: ['docs'] })
  }
}
</script>

<template>
  <VPageHeader :title="library?.spec.title ?? '文档'">
    <template #actions>
      <VButton size="sm" @click="handleBack">
        <template #icon>
          <RiArrowLeftLine />
        </template>
        返回文档库
      </VButton>
      <VButton type="secondary" @click="handleCreate()">
        <template #icon>
          <IconAddCircle />
        </template>
        新建文档
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard :body-class="['!p-0']">
      <VLoading v-if="isLoading" />

      <VEmpty
        v-else-if="isEmpty"
        message="当前文档库还没有文档，你可以新建一篇开始撰写。"
        title="暂无文档"
      >
        <template #actions>
          <VButton @click="handleCreate()">新建文档</VButton>
        </template>
      </VEmpty>

      <DraggableTree
        v-else
        v-model="treeData"
        children-key="children"
        :indent="24"
        tree-line
        class="mtree p-2"
        @drop="handleDrop"
      >
        <template #default="{ node }">
          <div class="flex flex-1 items-center justify-between gap-2 py-1.5 pr-2">
            <div class="flex min-w-0 items-center gap-2">
              <span class="truncate text-sm font-medium text-gray-900">
                {{ node.doc.spec.title }}
              </span>
              <span class="truncate text-xs text-gray-500">
                {{ node.doc.spec.slug }}
              </span>
              <VStatusDot
                v-tooltip="node.doc.spec.published ? '已发布' : '草稿'"
                :state="node.doc.spec.published ? 'success' : 'default'"
              />
            </div>
            <VDropdown>
              <IconMore class="cursor-pointer text-gray-500 hover:text-gray-900" />
              <template #popper>
                <VDropdownItem @click="handleCreate(node.doc.metadata.name)">
                  新建子文档
                </VDropdownItem>
                <VDropdownItem @click="handleEdit(node.doc)">编辑</VDropdownItem>
                <VDropdownItem type="danger" @click="handleDelete(node.doc)">
                  删除
                </VDropdownItem>
              </template>
            </VDropdown>
          </div>
        </template>
      </DraggableTree>
    </VCard>
  </div>
</template>

<style scoped>
.mtree :deep(.he-tree-node-inner) {
  border-radius: 4px;
}
.mtree :deep(.he-tree-node-inner:hover) {
  background-color: #f9fafb;
}
</style>
