import { computed, ref } from 'vue'
import type { DocLibrary } from '../api/generated'

export const POST_IMPORT_PERMISSION = 'plugin:my-docs:posts:import'
export const POST_IMPORT_ENDPOINT = '/apis/console.api.my-docs.tsdaer.run/v1alpha1/post-imports'
export const MAX_IMPORT_POSTS = 100

export interface ImportPost {
  name: string
  title: string
  published: boolean
}

export interface PostImportRequest {
  postNames: string[]
  targetLibraryName?: string
  newLibraryTitle?: string
  newLibrarySlug?: string
  publish: boolean
}

export interface PostImportReport {
  libraryName: string | null
  librarySlug: string | null
  imported: number
  updated: number
  failed: number
  items: {
    postName: string
    title: string
    status: 'created' | 'updated' | 'failed'
    docName: string | null
    reason: string | null
    warnings: string[]
  }[]
}

export interface PostImportApi {
  listPosts(page: number, keyword: string): Promise<{ items: ImportPost[]; total: number }>
  listLibraries(page: number): Promise<{ items: DocLibrary[]; total: number }>
  importPosts(request: PostImportRequest): Promise<PostImportReport>
  onImported(): Promise<void>
}

function errorMessage(error: unknown): string {
  const detail = (error as { response?: { data?: { detail?: unknown } } })?.response?.data?.detail
  return typeof detail === 'string'
    ? detail
    : error instanceof Error
      ? error.message
      : '导入失败，请重试。'
}

/** UI workflow isolated from host globals so paging, selection and retries are testable. */
export function createPostImport(api: PostImportApi, library?: DocLibrary) {
  const keyword = ref('')
  const page = ref(1)
  const posts = ref<ImportPost[]>([])
  const total = ref(0)
  const loadingPosts = ref(false)
  const loadingLibraries = ref(false)
  const libraries = ref<DocLibrary[]>(library ? [library] : [])
  const selected = ref(new Map<string, string>())
  const target = ref<'existing' | 'new'>(library ? 'existing' : 'new')
  const targetLibraryName = ref(library?.metadata.name ?? '')
  const newLibraryTitle = ref('')
  const newLibrarySlug = ref('')
  const publish = ref(false)
  const submitting = ref(false)
  const error = ref('')
  const postError = ref('')
  const libraryError = ref('')
  const report = ref<PostImportReport>()
  let requestId = 0

  const canSubmit = computed(
    () =>
      !submitting.value &&
      !report.value &&
      selected.value.size > 0 &&
      (target.value === 'existing' ? !!targetLibraryName.value : !!newLibraryTitle.value.trim()),
  )

  async function loadPosts() {
    const id = ++requestId
    loadingPosts.value = true
    postError.value = ''
    try {
      const response = await api.listPosts(page.value, keyword.value.trim())
      if (id === requestId) {
        posts.value = response.items
        total.value = response.total
      }
    } catch (cause) {
      if (id === requestId) {
        posts.value = []
        total.value = 0
        postError.value = errorMessage(cause)
      }
    } finally {
      if (id === requestId) loadingPosts.value = false
    }
  }

  async function search() {
    page.value = 1
    await loadPosts()
  }

  async function changePage(next: number) {
    page.value = next
    await loadPosts()
  }

  async function loadLibraries() {
    loadingLibraries.value = true
    libraryError.value = ''
    try {
      const all: DocLibrary[] = []
      let current = 1
      while (true) {
        const response = await api.listLibraries(current++)
        all.push(...response.items)
        if (all.length >= response.total || response.items.length === 0) break
      }
      libraries.value = all
    } catch (cause) {
      libraryError.value = errorMessage(cause)
    } finally {
      loadingLibraries.value = false
    }
  }

  function toggle(post: ImportPost) {
    if (submitting.value) return
    if (selected.value.has(post.name)) {
      selected.value.delete(post.name)
    } else if (selected.value.size < MAX_IMPORT_POSTS) {
      selected.value.set(post.name, post.title)
    } else {
      error.value = '单次最多选择 100 篇文章。'
    }
  }

  async function submit() {
    if (!canSubmit.value) return
    submitting.value = true
    error.value = ''
    try {
      const result = await api.importPosts({
        postNames: [...selected.value.keys()],
        ...(target.value === 'existing'
          ? { targetLibraryName: targetLibraryName.value }
          : {
              newLibraryTitle: newLibraryTitle.value.trim(),
              newLibrarySlug: newLibrarySlug.value.trim() || undefined,
            }),
        publish: publish.value,
      })
      report.value = result
      // Keep the completed report even if cache refresh fails; avoid accidentally resubmitting.
      try {
        await api.onImported()
      } catch {
        error.value = '导入结果已保存，列表刷新失败，请手动刷新。'
      }
    } catch (cause) {
      error.value = errorMessage(cause)
    } finally {
      submitting.value = false
    }
  }

  function retryFailed() {
    if (!report.value || submitting.value) return
    selected.value = new Map(
      report.value.items
        .filter((item) => item.status === 'failed')
        .map((item) => [item.postName, item.title]),
    )
    if (report.value.libraryName) {
      target.value = 'existing'
      targetLibraryName.value = report.value.libraryName
    }
    report.value = undefined
    error.value = ''
  }

  return {
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
  }
}
