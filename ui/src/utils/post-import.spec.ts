import { describe, it, expect, vi } from 'vitest'
import { createPostImport, type PostImportApi, type PostImportReport } from './post-import'
import type { DocLibrary } from '../api/generated'

const library: DocLibrary = {
  apiVersion: 'my-docs.tsdaer.run/v1alpha1',
  kind: 'DocLibrary',
  metadata: { name: 'lib' },
  spec: { title: 'Library', slug: 'library' },
}
const post = (name: string) => ({ name, title: `文章 ${name}`, published: false })
const completed: PostImportReport = {
  libraryName: 'lib',
  librarySlug: 'library',
  imported: 1,
  updated: 1,
  failed: 1,
  items: [
    { postName: 'a', title: 'A', status: 'created', docName: 'doc-a', reason: null, warnings: [] },
    {
      postName: 'b',
      title: 'B',
      status: 'updated',
      docName: 'doc-b',
      reason: null,
      warnings: ['转换提示'],
    },
    {
      postName: 'c',
      title: 'C',
      status: 'failed',
      docName: null,
      reason: '读取失败',
      warnings: [],
    },
  ],
}
function setup(library?: DocLibrary) {
  const api: PostImportApi = {
    listPosts: vi.fn(async () => ({ items: [post('a')], total: 21 })),
    listLibraries: vi.fn(async () => ({ items: [library!], total: 1 })),
    importPosts: vi.fn(async () => completed),
    onImported: vi.fn(async () => {}),
  }
  return { api, state: createPostImport(api, library) }
}

describe('article import workflow', () => {
  it('defaults to drafts and preselects the current library', () => {
    const { state } = setup(library)
    expect(state.publish.value).toBe(false)
    expect(state.target.value).toBe('existing')
    expect(state.targetLibraryName.value).toBe('lib')
    expect(state.canSubmit.value).toBe(false)
  })

  it('searches from page one and retains selections across pages and searches', async () => {
    const { api, state } = setup(library)
    await state.loadPosts()
    state.toggle(state.posts.value[0])
    vi.mocked(api.listPosts).mockResolvedValue({ items: [post('b')], total: 21 })
    await state.changePage(2)
    state.toggle(state.posts.value[0])
    state.keyword.value = '  文章 C  '
    await state.search()
    expect(api.listPosts).toHaveBeenLastCalledWith(1, '文章 C')
    expect(state.page.value).toBe(1)
    expect([...state.selected.value.keys()]).toEqual(['a', 'b'])
  })

  it('discards stale search responses', async () => {
    const { api, state } = setup()
    let release!: (value: { items: ReturnType<typeof post>[]; total: number }) => void
    vi.mocked(api.listPosts).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          release = resolve
        }),
    )
    const first = state.loadPosts()
    vi.mocked(api.listPosts).mockResolvedValue({ items: [post('new')], total: 1 })
    await state.search()
    release({ items: [post('old')], total: 1 })
    await first
    expect(state.posts.value[0].name).toBe('new')
  })

  it('limits selection to 100 but still allows deselecting', () => {
    const { state } = setup(library)
    for (let i = 0; i < 101; i++) state.toggle(post(String(i)))
    expect(state.selected.value.size).toBe(100)
    expect(state.error.value).toContain('100')
    state.toggle(post('0'))
    state.toggle(post('100'))
    expect(state.selected.value.size).toBe(100)
    expect(state.selected.value.has('100')).toBe(true)
  })

  it('loads libraries beyond the first page', async () => {
    const { api, state } = setup(library)
    vi.mocked(api.listLibraries)
      .mockResolvedValueOnce({ items: [library], total: 2 })
      .mockResolvedValueOnce({ items: [{ ...library, metadata: { name: 'second' } }], total: 2 })
    await state.loadLibraries()
    expect(api.listLibraries).toHaveBeenNthCalledWith(2, 2)
    expect(state.libraries.value).toHaveLength(2)
  })

  it('requires a valid target and sends only the chosen target mode', async () => {
    const { api, state } = setup()
    state.toggle(post('a'))
    expect(state.canSubmit.value).toBe(false)
    state.newLibraryTitle.value = ' New '
    state.newLibrarySlug.value = ' new '
    state.publish.value = true
    await state.submit()
    expect(api.importPosts).toHaveBeenCalledWith({
      postNames: ['a'],
      newLibraryTitle: 'New',
      newLibrarySlug: 'new',
      publish: true,
    })
    expect(state.report.value).toEqual(completed)
  })

  it('blocks double submits and selection changes while importing', async () => {
    const { api, state } = setup(library)
    state.toggle(post('a'))
    let resolve!: (value: PostImportReport) => void
    vi.mocked(api.importPosts).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const first = state.submit()
    expect(state.submitting.value).toBe(true)
    state.toggle(post('b'))
    await state.submit()
    expect(api.importPosts).toHaveBeenCalledTimes(1)
    expect([...state.selected.value.keys()]).toEqual(['a'])
    resolve(completed)
    await first
    expect(state.submitting.value).toBe(false)
    expect(api.onImported).toHaveBeenCalledTimes(1)
  })

  it('retains per-article results and retries failures into the already created library', async () => {
    const { api, state } = setup()
    state.newLibraryTitle.value = 'New'
    state.toggle(post('a'))
    await state.submit()
    expect(state.report.value?.items[1].warnings).toEqual(['转换提示'])
    state.retryFailed()
    expect(state.report.value).toBeUndefined()
    expect([...state.selected.value.keys()]).toEqual(['c'])
    expect(state.target.value).toBe('existing')
    await state.submit()
    expect(api.importPosts).toHaveBeenLastCalledWith({
      postNames: ['c'],
      targetLibraryName: 'lib',
      publish: false,
    })
  })

  it('shows request errors and allows retry', async () => {
    const { api, state } = setup(library)
    state.toggle(post('a'))
    vi.mocked(api.importPosts).mockRejectedValueOnce({
      response: { data: { detail: '目标库不存在' } },
    })
    await state.submit()
    expect(state.error.value).toBe('目标库不存在')
    expect(state.submitting.value).toBe(false)
    expect(state.report.value).toBeUndefined()
    await state.submit()
    expect(state.report.value).toEqual(completed)
  })

  it('preserves the completed report if refreshing cached lists fails', async () => {
    const { api, state } = setup(library)
    state.toggle(post('a'))
    vi.mocked(api.onImported).mockRejectedValue(new Error('offline'))
    await state.submit()
    expect(state.report.value).toEqual(completed)
    expect(state.error.value).toContain('列表刷新失败')
  })
})
