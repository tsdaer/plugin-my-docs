<script setup lang="ts">
import { VButton, VCard, VPageHeader, VEmpty, VLoading, Dialog, Toast, IconAddCircle } from '@halo-dev/components'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { axiosInstance } from '@halo-dev/api-client'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import { DocV1alpha1Api, DocLibraryV1alpha1Api } from '@/api/generated'
import type { Doc } from '@/api/generated'
import {
  buildDocTree,
  flattenPositions,
  moveNode,
  findNode,
  isSelfOrDescendant,
  type DocTreeNode,
  type DropZone,
} from '@/utils/doc-tree'
import DocTreeNodeComp from '@/components/DocTreeNode.vue'

const DOC_ENDPOINT = '/apis/console.api.docs.halo.run/v1alpha1/docs'
// 树需要全量节点才能组装，暂不分页；超出上限记录并提示。
const MAX_DOCS = 200

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

const docApi = new DocV1alpha1Api(undefined, '', axiosInstance)
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)

const libraryName = computed(() => route.params.libraryName as string)

// 树数据；由查询结果组装，拖拽会替换为 moveNode 的结果。
const treeData = ref<DocTreeNode[]>([])
// 以 name 为键的原始文档快照，用于拖拽后对比 parent/priority 是否变化。
const docByName = ref<Map<string, Doc>>(new Map())
const saving = ref(false)

// 展开状态：默认全部展开。
const expanded = ref<Set<string>>(new Set())

// 原生拖拽的瞬时状态。
const draggingName = ref('')
const overName = ref('')
const overZone = ref<'' | DropZone>('')

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
    // 默认展开所有含子节点的节点。
    const next = new Set<string>()
    const collect = (nodes: DocTreeNode[]) => {
      for (const n of nodes) {
        if (n.children.length) {
          next.add(n.doc.metadata.name)
          collect(n.children)
        }
      }
    }
    collect(treeData.value)
    expanded.value = next
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

function handleEdit(node: DocTreeNode) {
  router.push({
    name: 'DocEditor',
    params: { libraryName: libraryName.value },
    query: { name: node.doc.metadata.name },
  })
}

function handleDelete(node: DocTreeNode) {
  Dialog.warning({
    title: '确定要删除该文档吗？',
    description: `文档「${node.doc.spec.title}」将被删除，其子文档会上浮为顶层。该操作不可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      await docApi.deleteDoc({ name: node.doc.metadata.name })
      Toast.success('删除成功')
      await queryClient.invalidateQueries({ queryKey: ['docs'] })
    },
  })
}

function handleToggle(name: string) {
  const next = new Set(expanded.value)
  if (next.has(name)) {
    next.delete(name)
  } else {
    next.add(name)
  }
  expanded.value = next
}

function onNodeDragStart(name: string) {
  draggingName.value = name
}

function onNodeDragEnd() {
  draggingName.value = ''
  overName.value = ''
  overZone.value = ''
}

function onNodeDragOver(payload: { name: string; zone: DropZone }) {
  // 拖到自身或自己的子树上时不给放置反馈。
  const source = draggingName.value ? findNode(treeData.value, draggingName.value) : undefined
  if (source && isSelfOrDescendant(source, payload.name)) {
    overName.value = ''
    overZone.value = ''
    return
  }
  overName.value = payload.name
  overZone.value = payload.zone
}

async function onNodeDrop() {
  const sourceName = draggingName.value
  const targetName = overName.value
  const zone = overZone.value
  onNodeDragEnd()

  if (!sourceName || !targetName || !zone || sourceName === targetName) {
    return
  }

  const next = moveNode(treeData.value, sourceName, targetName, zone)
  if (next === treeData.value) {
    return // 非法移动，moveNode 原样返回
  }
  treeData.value = next
  await persistPositions()
}

// 对比新树的期望位置与原值，仅对 parent/priority 变化的节点即时保存。
async function persistPositions() {
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

      <div v-else class="p-2">
        <DocTreeNodeComp
          :nodes="treeData"
          :dragging-name="draggingName"
          :over-name="overName"
          :over-zone="overZone"
          :expanded="expanded"
          @toggle="handleToggle"
          @open="handleEdit"
          @create-child="handleCreate"
          @edit="handleEdit"
          @delete="handleDelete"
          @node-dragstart="onNodeDragStart"
          @node-dragend="onNodeDragEnd"
          @node-dragover="onNodeDragOver"
          @node-drop="onNodeDrop"
        />
      </div>
    </VCard>
  </div>
</template>
