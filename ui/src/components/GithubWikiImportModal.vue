<script setup lang="ts">
import { VModal, VButton, VSpace, Toast } from '@halo-dev/components'
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { axiosInstance } from '@halo-dev/api-client'
import { isAxiosError } from 'axios'
import { DocLibraryV1alpha1Api } from '@/api/generated'
import type { DocLibrary } from '@/api/generated'

interface WikiImportReport {
  libraryName: string
  librarySlug: string
  imported: number
  skipped: number
  slugAdjusted: number
  unresolvedLinks: number
  warnings: string[]
}

const props = withDefaults(
  defineProps<{
    library?: DocLibrary
  }>(),
  {
    library: undefined,
  },
)

const emit = defineEmits<{
  (e: 'close'): void
}>()

const WIKI_IMPORT_ENDPOINT = '/apis/console.api.my-docs.tsdaer.run/v1alpha1/github-wiki-imports'

const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)
const queryClient = useQueryClient()

const source = ref<'repository' | 'zip'>('repository')
const target = ref<'existing' | 'new'>(props.library ? 'existing' : 'new')
const selectedLibraryName = ref(props.library?.metadata?.name ?? '')

const repoUrl = ref('')
const token = ref('')
const selectedFile = ref<File | null>(null)
const newLibraryTitle = ref('')
const newLibrarySlug = ref('')
const publish = ref(true)

const submitting = ref(false)
const errorMessage = ref('')
const report = ref<WikiImportReport | null>(null)

const { data: libraries } = useQuery({
  queryKey: ['doc-libraries-for-wiki-import'],
  queryFn: async () => {
    const { data } = await libraryApi.listDocLibrary({
      page: 1,
      size: 200,
      sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    return data.items
  },
})

const libraryOptions = computed(() =>
  (libraries.value ?? []).map((library) => ({
    label: `${library.spec.title} (${library.spec.slug})`,
    value: library.metadata.name,
  })),
)

const sourceTabs = [
  { value: 'repository', label: '从仓库拉取' },
  { value: 'zip', label: '上传压缩包' },
] as const

const targetTabs = [
  { value: 'existing', label: '导入到已有库' },
  { value: 'new', label: '新建文档库' },
] as const

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
}

function extractErrorMessage(error: unknown): string {
  if (isAxiosError(error)) {
    const detail = error.response?.data?.detail as string | undefined
    if (detail) {
      return detail
    }
  }
  if (error instanceof Error) {
    return error.message
  }
  return '导入失败，请稍后重试。'
}

function validate(): string | null {
  if (source.value === 'repository' && !repoUrl.value.trim()) {
    return '请填写 GitHub 仓库地址。'
  }
  if (source.value === 'zip' && !selectedFile.value) {
    return '请选择要上传的 Wiki 压缩包。'
  }
  if (target.value === 'existing' && !selectedLibraryName.value) {
    return '请选择要导入的文档库。'
  }
  if (target.value === 'new' && !newLibraryTitle.value.trim()) {
    return '请填写新建文档库的标题。'
  }
  return null
}

async function handleImport() {
  const invalid = validate()
  if (invalid) {
    errorMessage.value = invalid
    return
  }
  errorMessage.value = ''
  submitting.value = true
  try {
    let response
    if (source.value === 'repository') {
      response = await axiosInstance.post<WikiImportReport>(`${WIKI_IMPORT_ENDPOINT}/repository`, {
        repoUrl: repoUrl.value.trim(),
        token: token.value.trim() || undefined,
        targetLibraryName: target.value === 'existing' ? selectedLibraryName.value : undefined,
        newLibraryTitle: target.value === 'new' ? newLibraryTitle.value.trim() : undefined,
        newLibrarySlug:
          target.value === 'new' && newLibrarySlug.value.trim()
            ? newLibrarySlug.value.trim()
            : undefined,
        publish: publish.value,
      })
    } else {
      const form = new FormData()
      form.append('file', selectedFile.value!)
      form.append('publish', String(publish.value))
      if (target.value === 'existing') {
        form.append('targetLibraryName', selectedLibraryName.value)
      } else {
        form.append('newLibraryTitle', newLibraryTitle.value.trim())
        if (newLibrarySlug.value.trim()) {
          form.append('newLibrarySlug', newLibrarySlug.value.trim())
        }
      }
      response = await axiosInstance.post<WikiImportReport>(`${WIKI_IMPORT_ENDPOINT}/zip`, form)
    }
    report.value = response.data
    await queryClient.invalidateQueries({ queryKey: ['docs'] })
    await queryClient.invalidateQueries({ queryKey: ['doc-libraries'] })
    Toast.success('GitHub Wiki 导入完成')
  } catch (error) {
    errorMessage.value = extractErrorMessage(error)
  } finally {
    submitting.value = false
  }
}

function handleFinish() {
  emit('close')
}
</script>

<template>
  <VModal title="从 GitHub Wiki 导入" :width="620" @close="emit('close')">
    <!-- 导入结果视图 -->
    <div v-if="report" class="space-y-3">
      <p class="text-sm">
        已导入到文档库
        <span class="font-medium">{{ report.librarySlug }}</span>
        ，共
        <span class="font-medium">{{ report.imported }}</span>
        篇文档。
      </p>
      <ul class="list-disc pl-5 text-sm text-gray-600">
        <li>跳过文件：{{ report.skipped }}（侧栏 / 页眉 / 页脚等非页面文件）</li>
        <li>别名自动去重：{{ report.slugAdjusted }} 篇</li>
        <li>未解析链接：{{ report.unresolvedLinks }} 个（已降级为纯文本）</li>
      </ul>
      <div v-if="report.warnings?.length" class="rounded border border-orange-200 bg-orange-50 p-3">
        <p class="mb-1 text-sm font-medium text-orange-700">注意事项</p>
        <ul class="list-disc space-y-1 pl-5 text-xs text-orange-700">
          <li v-for="warning in report.warnings" :key="warning">{{ warning }}</li>
        </ul>
      </div>
    </div>

    <!-- 导入表单视图 -->
    <div v-else class="space-y-5">
      <div>
        <p class="mb-2 text-sm font-medium text-gray-900">导入来源</p>
        <div class="mb-3 inline-flex overflow-hidden rounded border">
          <button
            v-for="tab in sourceTabs"
            :key="tab.value"
            type="button"
            class="px-4 py-1.5 text-sm"
            :class="
              source === tab.value
                ? 'bg-gray-800 text-white'
                : 'bg-white text-gray-600 hover:bg-gray-50'
            "
            @click="source = tab.value"
          >
            {{ tab.label }}
          </button>
        </div>

        <template v-if="source === 'repository'">
          <FormKit
            type="text"
            label="仓库地址"
            v-model="repoUrl"
            placeholder="owner/repo 或 https://github.com/owner/repo"
            help="由服务器直接拉取该仓库的 Wiki 页面（需要服务器可访问 GitHub）。"
          />
          <FormKit
            type="password"
            label="访问令牌（可选）"
            v-model="token"
            help="私有仓库的 Wiki 需要填写具有读取权限的 GitHub PAT。"
          />
        </template>
        <template v-else>
          <div class="space-y-2">
            <label class="block text-sm font-medium text-gray-900">Wiki 压缩包</label>
            <input
              type="file"
              accept=".zip"
              class="block w-full text-sm"
              @change="handleFileChange"
            />
            <p class="text-xs text-gray-500">
              在本地执行
              <code>git clone https://github.com/owner/repo.wiki.git</code>
              后将目录打包为 zip 上传，适合服务器无法直连 GitHub 的场景。
            </p>
          </div>
        </template>
      </div>

      <div>
        <p class="mb-2 text-sm font-medium text-gray-900">导入目标</p>
        <div class="mb-3 inline-flex overflow-hidden rounded border">
          <button
            v-for="tab in targetTabs"
            :key="tab.value"
            type="button"
            class="px-4 py-1.5 text-sm"
            :class="
              target === tab.value
                ? 'bg-gray-800 text-white'
                : 'bg-white text-gray-600 hover:bg-gray-50'
            "
            @click="target = tab.value"
          >
            {{ tab.label }}
          </button>
        </div>

        <FormKit
          v-if="target === 'existing'"
          type="select"
          label="目标文档库"
          v-model="selectedLibraryName"
          :options="libraryOptions"
          validation="required"
        />
        <template v-else>
          <FormKit
            type="text"
            label="文档库标题"
            v-model="newLibraryTitle"
            validation="required|length:1,100"
          />
          <FormKit
            type="text"
            label="别名（可选）"
            v-model="newLibrarySlug"
            help="留空时按标题自动生成；全局唯一，冲突时报错。"
          />
        </template>
      </div>

      <label class="flex items-center gap-2 text-sm">
        <input type="checkbox" v-model="publish" class="rounded" />
        导入后立即发布（关闭则保持草稿状态）
      </label>

      <p
        v-if="errorMessage"
        class="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-600"
      >
        {{ errorMessage }}
      </p>
    </div>

    <template #footer>
      <VSpace>
        <VButton v-if="report" type="secondary" @click="handleFinish">完成</VButton>
        <template v-else>
          <VButton type="secondary" :loading="submitting" @click="handleImport"> 开始导入 </VButton>
          <VButton :disabled="submitting" @click="emit('close')">取消</VButton>
        </template>
      </VSpace>
    </template>
  </VModal>
</template>
