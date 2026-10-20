<script setup lang="ts">
import { VModal, VButton, VSpace, VLoading } from '@halo-dev/components'
import { axiosInstance, consoleApiClient } from '@halo-dev/api-client'
import { useQueryClient } from '@tanstack/vue-query'
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { DocLibraryV1alpha1Api, type DocLibrary } from '@/api/generated'
import { createPostImport, POST_IMPORT_ENDPOINT, type PostImportReport } from '@/utils/post-import'

const props = defineProps<{ library?: DocLibrary }>()
const emit = defineEmits<{ (e: 'close'): void }>()
const router = useRouter()
const queryClient = useQueryClient()
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)
const {
  keyword,
  page,
  posts,
  total,
  loadingPosts,
  loadingLibraries,
  libraries,
  selected,
  target,
  targetLibraryName,
  newLibraryTitle,
  newLibrarySlug,
  publish,
  submitting,
  error,
  postError,
  libraryError,
  report,
  canSubmit,
  loadPosts,
  search,
  changePage,
  loadLibraries,
  toggle,
  submit,
  retryFailed,
} = createPostImport(
  {
    async listPosts(current, searchKeyword) {
      const { data } = await consoleApiClient.content.post.listPosts({
        page: current,
        size: 20,
        keyword: searchKeyword || undefined,
        labelSelector: ['content.halo.run/deleted=false'],
        sort: ['metadata.creationTimestamp,desc'],
      })
      return {
        items: data.items.map(({ post }) => ({
          name: post.metadata.name,
          title: post.spec.title,
          published: post.metadata.labels?.['content.halo.run/published'] === 'true',
        })),
        total: data.total,
      }
    },
    async listLibraries(current) {
      const { data } = await libraryApi.listDocLibrary({
        page: current,
        size: 100,
        sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
      })
      return data
    },
    async importPosts(request) {
      const { data } = await axiosInstance.post<PostImportReport>(POST_IMPORT_ENDPOINT, request)
      return data
    },
    async onImported() {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['docs'] }),
        queryClient.invalidateQueries({ queryKey: ['doc-libraries'] }),
        queryClient.invalidateQueries({ queryKey: ['doc-libraries-for-wiki-import'] }),
      ])
    },
  },
  props.library,
)

onMounted(() => {
  void loadPosts()
  void loadLibraries()
})

function close() {
  if (!submitting.value) emit('close')
}

function openLibrary() {
  if (!report.value?.libraryName) return
  void router.push({ name: 'DocList', params: { libraryName: report.value.libraryName } })
  close()
}

const statusLabels = { created: '新建', updated: '更新', failed: '失败' }
</script>

<template>
  <VModal title="导入本站文章" :width="760" @close="close">
    <div v-if="report" class="space-y-3">
      <p class="text-sm" role="status">
        新建 {{ report.imported }} 篇，更新 {{ report.updated }} 篇，失败 {{ report.failed }} 篇。
        <span v-if="report.librarySlug">目标库：{{ report.librarySlug }}</span>
      </p>
      <ul class="max-h-96 space-y-3 overflow-auto text-sm">
        <li v-for="item in report.items" :key="item.postName" class="rounded border p-3">
          <p :class="item.status === 'failed' ? 'text-red-600' : 'text-gray-800'">
            {{ statusLabels[item.status] }} · {{ item.title }}
          </p>
          <p v-if="item.reason" class="mt-1 text-red-600">{{ item.reason }}</p>
          <p v-for="warning in item.warnings" :key="warning" class="mt-1 text-xs text-amber-700">
            {{ warning }}
          </p>
        </li>
      </ul>
    </div>
    <fieldset v-else :disabled="submitting" class="min-w-0 space-y-4">
      <p class="rounded bg-amber-50 p-3 text-sm text-amber-800">
        复制文章最新已保存内容，保留原文章。重复导入同一库会覆盖对应文档的标题和正文，
        保留其位置、别名和发布状态。
      </p>
      <form class="flex gap-2" @submit.prevent="search">
        <input
          v-model="keyword"
          aria-label="搜索文章标题"
          placeholder="搜索文章标题"
          class="min-w-0 flex-1 rounded border px-3 py-2 text-sm"
          type="search"
        />
        <VButton type="secondary" @click="search">搜索</VButton>
      </form>
      <p class="flex items-center justify-between text-xs text-gray-600">
        <span>已选 {{ selected.size }} / 100 篇（跨页保留选择）</span>
        <button type="button" :disabled="selected.size === 0" @click="selected.clear()">
          清空选择
        </button>
      </p>
      <VLoading v-if="loadingPosts" />
      <div v-else-if="postError" class="text-sm text-red-600" role="alert">
        {{ postError }} <button type="button" @click="loadPosts">重试</button>
      </div>
      <div v-else class="max-h-72 overflow-auto rounded border">
        <p v-if="posts.length === 0" class="p-4 text-sm text-gray-500">没有可选择的文章。</p>
        <label
          v-for="post in posts"
          :key="post.name"
          class="flex cursor-pointer items-center gap-3 border-b p-3 text-sm last:border-b-0"
        >
          <input
            type="checkbox"
            :checked="selected.has(post.name)"
            :disabled="!selected.has(post.name) && selected.size >= 100"
            @change="toggle(post)"
          />
          <span class="min-w-0 flex-1 break-words">{{ post.title }}</span>
          <span class="shrink-0 text-xs text-gray-500">{{
            post.published ? '已发布' : '未发布'
          }}</span>
        </label>
      </div>
      <div class="flex items-center justify-between text-xs text-gray-600">
        <span>共 {{ total }} 篇 · 第 {{ page }} 页</span>
        <div class="flex gap-3">
          <button type="button" :disabled="loadingPosts || page <= 1" @click="changePage(page - 1)">
            上一页
          </button>
          <button
            type="button"
            :disabled="loadingPosts || page * 20 >= total"
            @click="changePage(page + 1)"
          >
            下一页
          </button>
        </div>
      </div>
      <div class="flex gap-4 text-sm">
        <label><input v-model="target" type="radio" value="existing" /> 导入到已有库</label>
        <label><input v-model="target" type="radio" value="new" /> 新建文档库</label>
      </div>
      <div v-if="target === 'existing'" class="space-y-2">
        <label class="block text-sm"
          >目标文档库
          <select
            v-model="targetLibraryName"
            class="mt-1 block w-full rounded border p-2"
            :disabled="loadingLibraries"
          >
            <option value="" disabled>请选择文档库</option>
            <option v-for="item in libraries" :key="item.metadata.name" :value="item.metadata.name">
              {{ item.spec.title }} ({{ item.spec.slug }})
            </option>
          </select>
        </label>
        <p v-if="libraryError" class="text-xs text-red-600" role="alert">
          {{ libraryError }} <button type="button" @click="loadLibraries">重试</button>
        </p>
      </div>
      <template v-else>
        <label class="block text-sm"
          >文档库标题
          <input
            v-model="newLibraryTitle"
            maxlength="100"
            class="mt-1 block w-full rounded border p-2"
          />
        </label>
        <label class="block text-sm"
          >文档库别名（可选）
          <input
            v-model="newLibrarySlug"
            maxlength="100"
            placeholder="留空按标题生成，自动处理重名"
            class="mt-1 block w-full rounded border p-2"
          />
        </label>
      </template>
      <label class="flex items-center gap-2 text-sm">
        <input v-model="publish" type="checkbox" />立即发布新文档（默认保存为草稿）
      </label>
    </fieldset>
    <p v-if="error" role="alert" class="mt-3 rounded bg-red-50 p-3 text-sm text-red-600">
      {{ error }}
    </p>
    <template #footer>
      <VSpace>
        <template v-if="report">
          <VButton v-if="report.libraryName" type="secondary" @click="openLibrary"
            >进入目标库</VButton
          >
          <VButton v-if="report.failed" :disabled="submitting" @click="retryFailed"
            >重试失败文章</VButton
          >
          <VButton :disabled="submitting" @click="close">完成</VButton>
        </template>
        <template v-else>
          <VButton type="secondary" :loading="submitting" :disabled="!canSubmit" @click="submit"
            >开始导入</VButton
          >
          <VButton :disabled="submitting" @click="close">取消</VButton>
        </template>
      </VSpace>
    </template>
  </VModal>
</template>
