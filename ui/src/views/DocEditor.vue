<script setup lang="ts">
import { VButton, VPageHeader, VLoading, Dialog, Toast } from '@halo-dev/components'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { axiosInstance } from '@halo-dev/api-client'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import RiMenuFoldLine from '~icons/ri/menu-fold-line'
import RiMenuUnfoldLine from '~icons/ri/menu-unfold-line'
import { DocV1alpha1Api } from '@/api/generated'
import type { Doc } from '@/api/generated'
import { buildDocTree, type DocTreeNode } from '@/utils/doc-tree'
import MarkdownEditor from '@/components/MarkdownEditor.vue'
import DocNavTree from '@/components/DocNavTree.vue'

const DOC_ENDPOINT = '/apis/console.api.docs.halo.run/v1alpha1/docs'
const MAX_DOCS = 200

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

const docApi = new DocV1alpha1Api(undefined, '', axiosInstance)

const libraryName = computed(() => route.params.libraryName as string)
const docName = computed(() => (route.query.name as string) || '')
const parentName = computed(() => (route.query.parent as string) || '')
const isUpdate = computed(() => !!docName.value)

const loading = ref(false)
const saving = ref(false)

// 编辑既有文档时，保留原始 Doc 以在保存时合并 metadata / 未编辑字段。
const original = ref<Doc>()

interface FormState {
  title: string
  slug: string
  parent: string
  priority: number
  published: boolean
}

const formState = ref<FormState>(emptyForm())
const raw = ref('')

// 加载完成时的快照，用于 dirty 检测。
const baseline = ref('')

function emptyForm(): FormState {
  return {
    title: '',
    slug: '',
    parent: parentName.value,
    priority: 0,
    published: false,
  }
}

function snapshot(): string {
  return JSON.stringify({ ...formState.value, raw: raw.value })
}

const dirty = computed(() => snapshot() !== baseline.value)

// ---- 侧边导航树 ----
const { data: navData } = useQuery({
  queryKey: ['docs', libraryName],
  queryFn: async () => {
    const { data } = await docApi.listDoc({
      page: 1,
      size: MAX_DOCS,
      fieldSelector: [`spec.libraryName=${libraryName.value}`],
      sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    return data
  },
})

const navTree = computed<DocTreeNode[]>(() => buildDocTree(navData.value?.items ?? []))
const linkableDocs = computed(() =>
  (navData.value?.items ?? [])
    .map((doc) => ({
      title: doc.spec.title,
      slug: doc.spec.slug,
    }))
    .sort((a, b) => a.title.localeCompare(b.title, 'zh-Hans-CN')),
)
const navExpanded = ref<Set<string>>(new Set())
const collapsed = ref(false)

watch(
  navData,
  (value) => {
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
    collect(buildDocTree(value?.items ?? []))
    navExpanded.value = next
  },
  { immediate: true },
)

function handleNavToggle(name: string) {
  const next = new Set(navExpanded.value)
  if (next.has(name)) {
    next.delete(name)
  } else {
    next.add(name)
  }
  navExpanded.value = next
}

// 点击侧边树切换文档：有未保存改动先确认。
function handleNavSelect(name: string) {
  if (name === docName.value) {
    return
  }
  const go = () =>
    router.push({
      name: 'DocEditor',
      params: { libraryName: libraryName.value },
      query: { name },
    })
  if (dirty.value) {
    Dialog.warning({
      title: '放弃未保存的更改？',
      description: '当前文档有未保存的更改，切换后将丢失。',
      confirmType: 'danger',
      onConfirm: go,
    })
  } else {
    go()
  }
}

// ---- 文档加载 / 切换 ----
// 编辑页靠 route.query.name 驱动，同名路由切换不会重跑 onMounted，故抽出 loadDoc 并 watch。
async function loadDoc() {
  if (!isUpdate.value) {
    // 新建：重置为空表单。
    original.value = undefined
    formState.value = emptyForm()
    raw.value = ''
    baseline.value = snapshot()
    return
  }
  loading.value = true
  try {
    const { data } = await docApi.getDoc({ name: docName.value })
    original.value = data
    formState.value = {
      title: data.spec.title,
      slug: data.spec.slug,
      parent: data.spec.parent ?? '',
      priority: data.spec.priority ?? 0,
      published: data.spec.published ?? false,
    }
    raw.value = data.spec.raw ?? ''
    baseline.value = snapshot()
  } finally {
    loading.value = false
  }
}

onMounted(loadDoc)
watch(docName, loadDoc)

function handleBack() {
  router.push({ name: 'DocList', params: { libraryName: libraryName.value } })
}

async function handleSave() {
  const values = formState.value
  saving.value = true
  try {
    if (isUpdate.value && original.value) {
      const toUpdate: Doc = {
        ...original.value,
        spec: {
          ...original.value.spec,
          title: values.title,
          slug: values.slug,
          parent: values.parent || undefined,
          priority: values.priority,
          published: values.published,
          raw: raw.value,
          rawType: 'markdown',
        },
      }
      const { data } = await axiosInstance.put<Doc>(
        `${DOC_ENDPOINT}/${original.value.metadata.name}`,
        toUpdate,
      )
      original.value = data
    } else {
      const toCreate: Doc = {
        apiVersion: 'docs.halo.run/v1alpha1',
        kind: 'Doc',
        metadata: {
          generateName: 'doc-',
          name: '',
        },
        spec: {
          title: values.title,
          slug: values.slug,
          libraryName: libraryName.value,
          parent: values.parent || undefined,
          priority: values.priority,
          published: values.published,
          raw: raw.value,
          rawType: 'markdown',
        },
      }
      const { data } = await axiosInstance.post<Doc>(DOC_ENDPOINT, toCreate)
      original.value = data
      await router.replace({
        name: 'DocEditor',
        params: { libraryName: libraryName.value },
        query: { name: data.metadata.name },
      })
    }
    // 保存成功后刷新 baseline，dirty 归零。
    baseline.value = snapshot()
    await queryClient.invalidateQueries({ queryKey: ['docs'] })
    Toast.success('保存成功')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <VPageHeader :title="isUpdate ? '编辑文档' : '新建文档'">
    <template #actions>
      <VButton size="sm" @click="handleBack">
        <template #icon>
          <RiArrowLeftLine />
        </template>
        返回
      </VButton>
      <VButton type="secondary" :loading="saving" @click="handleSave"> 保存 </VButton>
    </template>
  </VPageHeader>

  <div class="doc-editor-layout m-0 md:mx-4 md:mt-4">
    <aside v-if="!collapsed" class="doc-editor-aside">
      <div class="doc-editor-aside-head">
        <span class="text-sm font-medium text-gray-700">文档目录</span>
        <RiMenuFoldLine
          v-tooltip="'收起目录'"
          class="cursor-pointer text-gray-500 hover:text-gray-900"
          @click="collapsed = true"
        />
      </div>
      <div class="doc-editor-aside-body">
        <DocNavTree
          :nodes="navTree"
          :active-name="docName"
          :expanded="navExpanded"
          @toggle="handleNavToggle"
          @select="handleNavSelect"
        />
      </div>
    </aside>

    <div class="doc-editor-main">
      <RiMenuUnfoldLine
        v-if="collapsed"
        v-tooltip="'展开目录'"
        class="mb-2 cursor-pointer text-gray-500 hover:text-gray-900"
        @click="collapsed = false"
      />

      <VLoading v-if="loading" />

      <template v-else>
        <FormKit
          id="doc-form"
          type="form"
          name="doc-form"
          :actions="false"
          @submit="handleSave"
        >
          <div class="doc-editor-meta">
            <div class="doc-editor-meta-title">
              <FormKit
                v-model="formState.title"
                type="text"
                name="title"
                label="标题"
                validation="required|length:1,200"
              />
            </div>
            <div class="doc-editor-meta-slug">
              <FormKit
                v-model="formState.slug"
                type="text"
                name="slug"
                label="别名"
                :validation="[['required'], ['matches', /^[a-z0-9-]+$/], ['length', 1, 200]]"
              />
            </div>
            <div class="doc-editor-meta-priority">
              <FormKit
                v-model="formState.priority"
                type="number"
                name="priority"
                label="排序"
                validation="number"
              />
            </div>
            <div class="doc-editor-meta-published">
              <FormKit
                v-model="formState.published"
                type="switch"
                name="published"
                label="发布"
              />
            </div>
          </div>
        </FormKit>

        <div class="doc-editor-editor">
          <MarkdownEditor
            v-model="raw"
            height="100%"
            :doc-links="linkableDocs"
          />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.doc-editor-layout {
  display: flex;
  gap: 16px;
  align-items: stretch;
  height: calc(100vh - 140px);
  min-height: 0;
  overflow: hidden;
}
.doc-editor-aside {
  width: 260px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  max-height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
}
.doc-editor-aside-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.doc-editor-aside-body {
  padding: 8px;
  overflow-y: auto;
}
.doc-editor-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* 元信息紧凑横向排列，给正文腾空间：标题占主，其余按内容宽。 */
.doc-editor-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 0 16px;
}
.doc-editor-meta-title {
  flex: 1 1 240px;
  min-width: 200px;
}
.doc-editor-meta-slug {
  flex: 1 1 200px;
  min-width: 160px;
}
.doc-editor-meta-priority {
  flex: 0 0 90px;
}
.doc-editor-meta-published {
  flex: 0 0 auto;
  align-self: center;
  padding-top: 4px;
}
/* 收紧 FormKit 默认的外边距，减少纵向占用。 */
.doc-editor-meta :deep(.formkit-outer) {
  margin-bottom: 8px;
}
.doc-editor-editor {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}
.doc-editor-editor :deep(.markdown-editor-root) {
  flex: 1;
  min-height: 0;
}
</style>
