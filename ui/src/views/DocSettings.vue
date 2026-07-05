<script setup lang="ts">
import { VButton, VCard, VLoading, VPageHeader, VSpace, Toast, IconSettings, Dialog } from '@halo-dev/components'
import { coreApiClient, axiosInstance } from '@halo-dev/api-client'
import type { ConfigMap } from '@halo-dev/api-client'
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import { DocLibraryV1alpha1Api, DocV1alpha1Api } from '@/api/generated'
import type { Doc, DocLibrary } from '@/api/generated'
import {
  buildMyDocsBackup,
  buildMyDocsBackupFilename,
  parseMyDocsBackup,
  type DocBackupRecord,
  type DocLibraryBackupRecord,
  type MyDocsBackupFile,
} from '@/utils/my-docs-backup'
import {
  MY_DOCS_CONFIG_GROUP,
  MY_DOCS_CONFIG_MAP_NAME,
  defaultMyDocsSettings,
  parseMyDocsSettings,
  stringifyMyDocsSettings,
  type LibraryFolderTitleSetting,
  type LibraryPageLayoutSetting,
  type LibraryPlacementSetting,
  type LibraryRowLayoutSetting,
  type MyDocsSettings,
} from '@/utils/my-docs-settings'

const DOC_ENDPOINT = '/apis/console.api.docs.halo.run/v1alpha1/docs'
const LIST_PAGE_SIZE = 200

const router = useRouter()
const queryClient = useQueryClient()
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)
const docApi = new DocV1alpha1Api(undefined, '', axiosInstance)

const { data: configMap, isLoading: isConfigLoading } = useQuery({
  queryKey: ['my-docs-settings-configmap'],
  queryFn: async () => {
    const { data } = await coreApiClient.configMap.listConfigMap({
      page: 1,
      size: 1,
      fieldSelector: [`metadata.name=${MY_DOCS_CONFIG_MAP_NAME}`],
    })
    return data.items[0]
  },
})

const { data: libraries, isLoading: isLibrariesLoading } = useQuery({
  queryKey: ['doc-libraries-for-settings'],
  queryFn: async () => {
    const { data } = await libraryApi.listDocLibrary({
      page: 1,
      size: 200,
      sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    return data.items
  },
})

const formKey = computed(() => configMap.value?.metadata.version ?? 'new')
const isLoading = computed(() => isConfigLoading.value || isLibrariesLoading.value)
const isExporting = ref(false)
const isImporting = ref(false)
const importInput = ref<HTMLInputElement | null>(null)

const settingsState = ref<MyDocsSettings>(parseMyDocsSettings())

watch(
  () => configMap.value?.data?.[MY_DOCS_CONFIG_GROUP],
  (raw) => {
    settingsState.value = parseMyDocsSettings(raw)
  },
  { immediate: true },
)

const libraryOptions = computed(() => [
  { label: '不指定', value: '' },
  ...(libraries.value ?? []).map((library) => ({
    label: library.spec.title,
    value: library.metadata.name,
  })),
])

const placementLibraryOptions = computed(() =>
  (libraries.value ?? []).map((library) => ({
    label: `${library.spec.title} (${library.spec.slug})`,
    value: library.metadata.name,
  })),
)

function createPageLayout(): LibraryPageLayoutSetting {
  const nextPage =
    Math.max(0, ...settingsState.value.libraryIndexPageLayouts.map((item) => item.page || 0)) + 1
  return {
    page: nextPage,
    maxRows: settingsState.value.libraryIndexDefaultMaxRows,
  }
}

function createRowLayout(): LibraryRowLayoutSetting {
  const nextRow =
    Math.max(0, ...settingsState.value.libraryIndexRowLayouts.map((item) => item.row || 0)) + 1
  return {
    row: nextRow,
    columns: settingsState.value.libraryIndexDefaultColumns,
  }
}

function createPlacement(): LibraryPlacementSetting {
  return {
    libraryName: placementLibraryOptions.value[0]?.value ?? '',
    row: 1,
    column: 1,
  }
}

function createFolderTitle(): LibraryFolderTitleSetting {
  return {
    row: 1,
    column: 1,
    title: '',
    description: '',
  }
}

function addPageLayout() {
  settingsState.value.libraryIndexPageLayouts = [
    ...settingsState.value.libraryIndexPageLayouts,
    createPageLayout(),
  ]
}

function addRowLayout() {
  settingsState.value.libraryIndexRowLayouts = [
    ...settingsState.value.libraryIndexRowLayouts,
    createRowLayout(),
  ]
}

function addPlacement() {
  settingsState.value.libraryIndexPlacements = [
    ...settingsState.value.libraryIndexPlacements,
    createPlacement(),
  ]
}

function addFolderTitle() {
  settingsState.value.libraryIndexFolderTitles = [
    ...settingsState.value.libraryIndexFolderTitles,
    createFolderTitle(),
  ]
}

function removePageLayout(index: number) {
  settingsState.value.libraryIndexPageLayouts = settingsState.value.libraryIndexPageLayouts.filter(
    (_, itemIndex) => itemIndex !== index,
  )
}

function removeRowLayout(index: number) {
  settingsState.value.libraryIndexRowLayouts = settingsState.value.libraryIndexRowLayouts.filter(
    (_, itemIndex) => itemIndex !== index,
  )
}

function removePlacement(index: number) {
  settingsState.value.libraryIndexPlacements = settingsState.value.libraryIndexPlacements.filter(
    (_, itemIndex) => itemIndex !== index,
  )
}

function removeFolderTitle(index: number) {
  settingsState.value.libraryIndexFolderTitles =
    settingsState.value.libraryIndexFolderTitles.filter((_, itemIndex) => itemIndex !== index)
}

function coordinateLabel(row?: number, column?: number): string {
  return `第 ${row || '-'} 行 / 第 ${column || '-'} 列`
}

function rowColumnsFor(settings: MyDocsSettings, row: number): number {
  const rowLayout = settings.libraryIndexRowLayouts.find((item) => item.row === row)
  return rowLayout?.columns || settings.libraryIndexDefaultColumns
}

function buildLayoutWarnings(settings: MyDocsSettings): string[] {
  const issues: string[] = []
  const libraryNames = new Set((libraries.value ?? []).map((library) => library.metadata.name))
  const visiblePlacementKeys = new Set<string>()
  const assignedLibraries = new Set<string>()

  for (const placement of settings.libraryIndexPlacements) {
    assignedLibraries.add(placement.libraryName)
    const maxColumns = rowColumnsFor(settings, placement.row)
    if (placement.column > maxColumns) {
      issues.push(
        `文档库坐标 ${coordinateLabel(placement.row, placement.column)} 超过了该行 ${maxColumns} 列，前台会隐藏该文档库。`,
      )
      continue
    }
    if (!libraryNames.has(placement.libraryName)) {
      issues.push(`文档库坐标 ${coordinateLabel(placement.row, placement.column)} 指向了不存在的文档库。`)
      continue
    }
    visiblePlacementKeys.add(`${placement.row}:${placement.column}`)
  }

  for (const folderTitle of settings.libraryIndexFolderTitles) {
    const maxColumns = rowColumnsFor(settings, folderTitle.row)
    if (folderTitle.column > maxColumns) {
      issues.push(
        `文件夹坐标 ${coordinateLabel(folderTitle.row, folderTitle.column)} 超过了该行 ${maxColumns} 列，前台会隐藏该文件夹入口。`,
      )
    }
  }

  const knownLibraries = (libraries.value ?? []).filter((library) =>
    !assignedLibraries.has(library.metadata.name),
  )
  const occupiedRows = new Map<number, number>()
  for (const key of visiblePlacementKeys) {
    const row = Number(key.split(':')[0])
    occupiedRows.set(row, (occupiedRows.get(row) || 0) + 1)
  }

  let row = 1
  let remaining = knownLibraries.length
  while (remaining > 0) {
    const columns = rowColumnsFor(settings, row)
    const occupied = occupiedRows.get(row) || 0
    const freeSlots = Math.max(0, columns - occupied)
    if (freeSlots > 0) {
      const filled = Math.min(freeSlots, remaining)
      occupiedRows.set(row, occupied + filled)
      remaining -= filled
    }
    row += 1
  }

  const maxConfiguredRow = Math.max(
    0,
    ...settings.libraryIndexRowLayouts.map((item) => item.row || 0),
    ...settings.libraryIndexPlacements.map((item) => item.row || 0),
    ...settings.libraryIndexFolderTitles.map((item) => item.row || 0),
    row - 1,
  )

  const blankRows: number[] = []
  for (let rowIndex = 1; rowIndex <= maxConfiguredRow; rowIndex += 1) {
    if ((occupiedRows.get(rowIndex) || 0) === 0) {
      blankRows.push(rowIndex)
    }
  }
  if (blankRows.length) {
    issues.push(`当前设置会保留空白行：${blankRows.map((item) => `第 ${item} 行`).join('、')}。`)
  }

  return Array.from(new Set(issues))
}

async function persistSettings(normalized: MyDocsSettings) {
  const finalSettings: MyDocsSettings = {
    ...normalized,
    defaultSort: normalized.defaultSort,
    defaultLibraryName: normalized.defaultLibraryName ?? '',
    libraryIndexDefaultColumns:
      Number(normalized.libraryIndexDefaultColumns)
      || defaultMyDocsSettings.libraryIndexDefaultColumns,
    libraryIndexDefaultMaxRows:
      Number(normalized.libraryIndexDefaultMaxRows)
      || defaultMyDocsSettings.libraryIndexDefaultMaxRows,
    libraryIndexPageLayouts: normalized.libraryIndexPageLayouts,
    libraryIndexRowLayouts: normalized.libraryIndexRowLayouts,
    libraryIndexPlacements: normalized.libraryIndexPlacements,
    libraryIndexFolderTitles: normalized.libraryIndexFolderTitles,
    renderContentTheme: normalized.renderContentTheme,
    renderCodeTheme:
      normalized.renderCodeTheme?.trim() || defaultMyDocsSettings.renderCodeTheme,
    renderLineNumber: !!normalized.renderLineNumber,
    renderAutoSpace: !!normalized.renderAutoSpace,
    renderGfmAutoLink: !!normalized.renderGfmAutoLink,
    renderFootnotes: !!normalized.renderFootnotes,
    renderMark: !!normalized.renderMark,
    renderFixTermTypo: !!normalized.renderFixTermTypo,
    renderParagraphBeginningSpace: !!normalized.renderParagraphBeginningSpace,
    renderCodeBlockPreview: !!normalized.renderCodeBlockPreview,
    renderMathBlockPreview: !!normalized.renderMathBlockPreview,
  }

  settingsState.value = finalSettings

  const next: ConfigMap = configMap.value
    ? {
        ...configMap.value,
        data: {
          ...(configMap.value.data ?? {}),
          [MY_DOCS_CONFIG_GROUP]: stringifyMyDocsSettings(finalSettings),
        },
      }
    : {
        apiVersion: 'v1alpha1',
        kind: 'ConfigMap',
        metadata: {
          name: MY_DOCS_CONFIG_MAP_NAME,
        },
        data: {
          [MY_DOCS_CONFIG_GROUP]: stringifyMyDocsSettings(finalSettings),
        },
      }

  if (configMap.value) {
    await coreApiClient.configMap.updateConfigMap({
      name: MY_DOCS_CONFIG_MAP_NAME,
      configMap: next,
    })
  } else {
    await coreApiClient.configMap.createConfigMap({
      configMap: next,
    })
  }

  Toast.success('设置已保存')
  await queryClient.invalidateQueries({ queryKey: ['my-docs-settings-configmap'] })
}

function buildExtensionConfigMap(rawSettings: string): ConfigMap {
  return configMap.value
    ? {
        ...configMap.value,
        data: {
          ...(configMap.value.data ?? {}),
          [MY_DOCS_CONFIG_GROUP]: rawSettings,
        },
      }
    : {
        apiVersion: 'v1alpha1',
        kind: 'ConfigMap',
        metadata: {
          name: MY_DOCS_CONFIG_MAP_NAME,
        },
        data: {
          [MY_DOCS_CONFIG_GROUP]: rawSettings,
        },
      }
}

function getErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}

async function listAllLibraries(): Promise<DocLibrary[]> {
  const items: DocLibrary[] = []
  let currentPage = 1
  let total = 0

  do {
    const { data } = await libraryApi.listDocLibrary({
      page: currentPage,
      size: LIST_PAGE_SIZE,
      sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    items.push(...(data.items ?? []))
    total = data.total ?? items.length
    currentPage += 1
  } while (items.length < total)

  return items
}

async function listAllDocs(): Promise<Doc[]> {
  const items: Doc[] = []
  let currentPage = 1
  let total = 0

  do {
    const { data } = await docApi.listDoc({
      page: currentPage,
      size: LIST_PAGE_SIZE,
      sort: ['spec.libraryName,asc', 'spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    items.push(...(data.items ?? []))
    total = data.total ?? items.length
    currentPage += 1
  } while (items.length < total)

  return items
}

function downloadBackup(backup: MyDocsBackupFile) {
  const text = JSON.stringify(backup, null, 2)
  const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = buildMyDocsBackupFilename(backup.exportedAt)
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

function toLibraryPayload(record: DocLibraryBackupRecord, current?: DocLibrary): DocLibrary {
  if (current) {
    return {
      ...current,
      spec: {
        ...current.spec,
        ...record.spec,
      },
    }
  }

  return {
    apiVersion: 'docs.halo.run/v1alpha1',
    kind: 'DocLibrary',
    metadata: {
      name: record.name,
    },
    spec: {
      ...record.spec,
    },
  }
}

function toDocPayload(record: DocBackupRecord, current?: Doc): Doc {
  if (current) {
    return {
      ...current,
      spec: {
        ...current.spec,
        ...record.spec,
        parent: record.spec.parent || undefined,
        publishTime: record.spec.publishTime || undefined,
      },
    }
  }

  return {
    apiVersion: 'docs.halo.run/v1alpha1',
    kind: 'Doc',
    metadata: {
      name: record.name,
    },
    spec: {
      ...record.spec,
      parent: record.spec.parent || undefined,
      publishTime: record.spec.publishTime || undefined,
    },
  }
}

async function handleExport() {
  isExporting.value = true
  try {
    const [allLibraries, allDocs] = await Promise.all([listAllLibraries(), listAllDocs()])
    const backup = buildMyDocsBackup(
      parseMyDocsSettings(configMap.value?.data?.[MY_DOCS_CONFIG_GROUP]),
      allLibraries,
      allDocs,
    )
    downloadBackup(backup)
    Toast.success(`已导出 ${backup.libraries.length} 个文档库和 ${backup.docs.length} 篇文档`)
  } catch (error) {
    Toast.warning(getErrorMessage(error, '导出失败'))
  } finally {
    isExporting.value = false
  }
}

function handleOpenImport() {
  if (isImporting.value) {
    return
  }
  importInput.value?.click()
}

async function restoreBackup(backup: MyDocsBackupFile) {
  const normalizedSettings = parseMyDocsSettings(JSON.stringify(backup.settings))
  const [currentLibraries, currentDocs] = await Promise.all([listAllLibraries(), listAllDocs()])
  const currentLibraryMap = new Map(currentLibraries.map((library) => [library.metadata.name, library]))
  const currentDocMap = new Map(currentDocs.map((doc) => [doc.metadata.name, doc]))
  const backupLibraryNames = new Set(backup.libraries.map((library) => library.name))
  const backupDocNames = new Set(backup.docs.map((doc) => doc.name))

  for (const doc of currentDocs) {
    if (!backupDocNames.has(doc.metadata.name)) {
      await docApi.deleteDoc({ name: doc.metadata.name })
    }
  }

  for (const library of backup.libraries) {
    const current = currentLibraryMap.get(library.name)
    const payload = toLibraryPayload(library, current)
    if (current) {
      await libraryApi.updateDocLibrary({
        name: library.name,
        docLibrary: payload,
      })
    } else {
      await libraryApi.createDocLibrary({
        docLibrary: payload,
      })
    }
  }

  for (const doc of backup.docs) {
    const current = currentDocMap.get(doc.name)
    const payload = toDocPayload(doc, current)
    if (current) {
      await axiosInstance.put<Doc>(`${DOC_ENDPOINT}/${doc.name}`, payload)
    } else {
      await axiosInstance.post<Doc>(DOC_ENDPOINT, payload)
    }
  }

  for (const library of currentLibraries) {
    if (!backupLibraryNames.has(library.metadata.name)) {
      await libraryApi.deleteDocLibrary({ name: library.metadata.name })
    }
  }

  const rawSettings = stringifyMyDocsSettings(normalizedSettings)
  const next = buildExtensionConfigMap(rawSettings)

  if (configMap.value) {
    await coreApiClient.configMap.updateConfigMap({
      name: MY_DOCS_CONFIG_MAP_NAME,
      configMap: next,
    })
  } else {
    await coreApiClient.configMap.createConfigMap({
      configMap: next,
    })
  }

  settingsState.value = normalizedSettings
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['my-docs-settings-configmap'] }),
    queryClient.invalidateQueries({ queryKey: ['doc-libraries'] }),
    queryClient.invalidateQueries({ queryKey: ['doc-libraries-for-settings'] }),
    queryClient.invalidateQueries({ queryKey: ['docs'] }),
  ])
}

async function handleImportFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''

  if (!file) {
    return
  }

  try {
    const backup = parseMyDocsBackup(await file.text())
    Dialog.warning({
      title: '确定要加载设置并恢复文档内容吗？',
      description: [
        `文件包含 ${backup.libraries.length} 个文档库、${backup.docs.length} 篇文档。`,
        '导入会覆盖当前文档设置。',
        '同名文档库与文档会被更新，备份文件中不存在的文档会被删除。',
        '这个过程不会自动回滚，请确认这是你要恢复的快照。',
      ].join('\n'),
      confirmType: 'danger',
      onConfirm: async () => {
        isImporting.value = true
        try {
          await restoreBackup(backup)
          Toast.success('设置和文档内容已恢复')
        } catch (error) {
          Toast.warning(getErrorMessage(error, '加载设置失败'))
        } finally {
          isImporting.value = false
        }
      },
    })
  } catch (error) {
    Toast.warning(getErrorMessage(error, '加载设置失败'))
  }
}

async function handleSubmit() {
  const normalized = parseMyDocsSettings(stringifyMyDocsSettings(settingsState.value))
  const issues = buildLayoutWarnings(normalized)

  if (issues.length) {
    Dialog.warning({
      title: '发现布局问题，是否强制保存？',
      description: issues.join('\n'),
      confirmType: 'danger',
      onConfirm: async () => {
        await persistSettings(normalized)
      },
    })
    return
  }

  await persistSettings(normalized)
}
</script>

<template>
  <VPageHeader title="文档设置">
    <template #icon>
      <IconSettings class="mr-2 self-center" />
    </template>
    <template #actions>
      <VButton size="sm" @click="router.push({ name: 'DocLibraries' })">
        <template #icon>
          <RiArrowLeftLine />
        </template>
        返回文档
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard>
      <VLoading v-if="isLoading" />

      <FormKit
        v-else
        :key="formKey"
        id="my-docs-settings-form"
        type="form"
        name="my-docs-settings-form"
        :actions="false"
        @submit="handleSubmit"
      >
        <div class="doc-settings-section">
          <h3 class="doc-settings-title">备份与恢复</h3>
          <p class="doc-settings-help">
            导出文件会包含当前设置、全部文档库以及文档 Markdown 内容。加载时会按快照覆盖并恢复这些数据。
          </p>
          <VSpace>
            <VButton type="secondary" :loading="isExporting" @click="handleExport">
              导出设置
            </VButton>
            <VButton :loading="isImporting" @click="handleOpenImport">
              加载设置
            </VButton>
          </VSpace>
          <input
            ref="importInput"
            accept=".json,application/json"
            class="hidden"
            type="file"
            @change="handleImportFileChange"
          >
        </div>

        <div class="doc-settings-section">
          <h3 class="doc-settings-title">基础设置</h3>
          <div class="doc-settings-grid doc-settings-grid--compact">
            <FormKit
              type="select"
              name="defaultSort"
              label="默认排序"
              v-model="settingsState.defaultSort"
              :options="[
                { label: '排序权重升序', value: 'priorityAsc' },
                { label: '创建时间倒序', value: 'createdDesc' },
                { label: '标题升序', value: 'titleAsc' },
              ]"
              validation="required"
            />
            <FormKit
              type="select"
              name="defaultLibraryName"
              label="默认文档库"
              v-model="settingsState.defaultLibraryName"
              :options="libraryOptions"
            />
          </div>
        </div>

        <div class="doc-settings-section">
          <h3 class="doc-settings-title">文档库首页布局</h3>
          <p class="doc-settings-help">
            每一行都会按照固定槽位宽度排布。默认每行 2 个；特定行可单独改列数，文档库也可指定到具体坐标。
            如果多个文档库落在同一个坐标，会折叠成文件夹卡片。
          </p>
          <div class="doc-settings-grid">
            <FormKit
              type="number"
              name="libraryIndexDefaultColumns"
              label="默认每行个数"
              v-model="settingsState.libraryIndexDefaultColumns"
              validation="required|min:1|max:12"
            />
            <FormKit
              type="number"
              name="libraryIndexDefaultMaxRows"
              label="默认每页最大行数"
              v-model="settingsState.libraryIndexDefaultMaxRows"
              validation="required|min:1|max:24"
            />
          </div>

          <div class="doc-settings-list-section">
            <div class="doc-settings-list-head">
              <h4>特定行列数</h4>
              <VButton size="sm" type="secondary" @click="addRowLayout">新增行设置</VButton>
            </div>
            <p class="doc-settings-help">这里的行号是跨页面的全局行号，例如第 6 行可能落在第 2 页。</p>
            <p
              v-if="!settingsState.libraryIndexRowLayouts.length"
              class="doc-settings-empty"
            >
              当前没有特定行列数设置，所有行都会使用默认每行个数。
            </p>
            <div
              v-for="(item, index) in settingsState.libraryIndexRowLayouts"
              :key="`row-layout-${index}`"
              class="doc-settings-list-item"
            >
              <div class="doc-settings-list-grid doc-settings-list-grid--compact">
                <label class="doc-settings-field">
                  <span>第几行</span>
                  <input v-model.number="item.row" min="1" type="number">
                </label>
                <label class="doc-settings-field">
                  <span>该行列数</span>
                  <input v-model.number="item.columns" min="1" max="24" type="number">
                </label>
              </div>
              <div class="doc-settings-list-meta">
                <span>{{ coordinateLabel(item.row, 1) }}</span>
                <VButton size="sm" @click="removeRowLayout(index)">删除</VButton>
              </div>
            </div>
          </div>

          <div class="doc-settings-list-section">
            <div class="doc-settings-list-head">
              <h4>特定页设置</h4>
              <VButton size="sm" type="secondary" @click="addPageLayout">新增页设置</VButton>
            </div>
            <p class="doc-settings-help">按页覆盖默认每页最大行数，例如第 2 页最多 1 行，第 3 页最多 4 行。</p>
            <p
              v-if="!settingsState.libraryIndexPageLayouts.length"
              class="doc-settings-empty"
            >
              当前没有特定页设置，所有页面都会使用默认每页最大行数。
            </p>
            <div
              v-for="(item, index) in settingsState.libraryIndexPageLayouts"
              :key="`page-layout-${index}`"
              class="doc-settings-list-item"
            >
              <div class="doc-settings-list-grid doc-settings-list-grid--compact">
                <label class="doc-settings-field">
                  <span>第几页</span>
                  <input v-model.number="item.page" min="1" type="number">
                </label>
                <label class="doc-settings-field">
                  <span>最大行数</span>
                  <input v-model.number="item.maxRows" min="1" max="24" type="number">
                </label>
              </div>
              <div class="doc-settings-list-meta">
                <span>第 {{ item.page || '-' }} 页</span>
                <VButton size="sm" @click="removePageLayout(index)">删除</VButton>
              </div>
            </div>
          </div>

          <div class="doc-settings-list-section">
            <div class="doc-settings-list-head">
              <h4>文档库坐标</h4>
              <VButton size="sm" type="secondary" @click="addPlacement">新增文档库坐标</VButton>
            </div>
            <p class="doc-settings-help">
              坐标按“第几行 / 第几列”填写。多个文档库可指定到同一个坐标，此时前台会渲染为文件夹卡片。
            </p>
            <p
              v-if="!settingsState.libraryIndexPlacements.length"
              class="doc-settings-empty"
            >
              当前没有手动坐标，未指定的文档库会按顺序自动填充。
            </p>
            <div
              v-for="(item, index) in settingsState.libraryIndexPlacements"
              :key="`placement-${index}`"
              class="doc-settings-list-item"
            >
              <div class="doc-settings-list-grid">
                <label class="doc-settings-field doc-settings-field--wide">
                  <span>文档库</span>
                  <select v-model="item.libraryName">
                    <option
                      v-for="option in placementLibraryOptions"
                      :key="option.value"
                      :value="option.value"
                    >
                      {{ option.label }}
                    </option>
                  </select>
                </label>
                <label class="doc-settings-field">
                  <span>第几行</span>
                  <input v-model.number="item.row" min="1" type="number">
                </label>
                <label class="doc-settings-field">
                  <span>第几列</span>
                  <input v-model.number="item.column" min="1" type="number">
                </label>
              </div>
              <div class="doc-settings-list-meta">
                <span>{{ coordinateLabel(item.row, item.column) }}</span>
                <VButton size="sm" @click="removePlacement(index)">删除</VButton>
              </div>
            </div>
          </div>

          <div class="doc-settings-list-section">
            <div class="doc-settings-list-head">
              <h4>文件夹名称</h4>
              <VButton size="sm" type="secondary" @click="addFolderTitle">新增坐标标题</VButton>
            </div>
            <p class="doc-settings-help">
              仅当某个坐标落入多个文档库时生效；未设置时，会使用该文件夹中排序最靠前的文档库名称。
            </p>
            <p
              v-if="!settingsState.libraryIndexFolderTitles.length"
              class="doc-settings-empty"
            >
              当前没有坐标标题。
            </p>
            <div
              v-for="(item, index) in settingsState.libraryIndexFolderTitles"
              :key="`folder-title-${index}`"
              class="doc-settings-list-item"
            >
              <div class="doc-settings-list-grid">
                <label class="doc-settings-field">
                  <span>第几行</span>
                  <input v-model.number="item.row" min="1" type="number">
                </label>
                <label class="doc-settings-field">
                  <span>第几列</span>
                  <input v-model.number="item.column" min="1" type="number">
                </label>
                <label class="doc-settings-field doc-settings-field--wide">
                  <span>文件夹名称</span>
                  <input v-model.trim="item.title" maxlength="100" type="text">
                </label>
                <label class="doc-settings-field doc-settings-field--wide">
                  <span>文件夹描述</span>
                  <input v-model.trim="item.description" maxlength="200" type="text">
                </label>
              </div>
              <div class="doc-settings-list-meta">
                <span>{{ coordinateLabel(item.row, item.column) }}</span>
                <VButton size="sm" @click="removeFolderTitle(index)">删除</VButton>
              </div>
            </div>
          </div>
        </div>

        <div class="doc-settings-section">
          <h3 class="doc-settings-title">文档页面渲染</h3>
          <p class="doc-settings-help">
            这些设置用于前台文档页面的渲染表现，不影响后台文档编辑器。
          </p>
          <div class="doc-settings-grid">
            <FormKit
              type="select"
              name="renderContentTheme"
              label="内容主题"
              v-model="settingsState.renderContentTheme"
              :options="[
                { label: 'Light', value: 'light' },
                { label: 'Dark', value: 'dark' },
                { label: 'WeChat', value: 'wechat' },
                { label: 'Ant Design', value: 'ant-design' },
              ]"
              validation="required"
            />
            <FormKit
              type="text"
              name="renderCodeTheme"
              label="代码主题"
              v-model="settingsState.renderCodeTheme"
              help="填写 Vditor / Chroma 代码主题名，例如 github、monokai。"
              validation="required|length:1,100"
            />
          </div>
          <div class="doc-settings-grid">
            <FormKit
              type="switch"
              name="renderLineNumber"
              label="代码行号"
              v-model="settingsState.renderLineNumber"
            />
            <FormKit
              type="switch"
              name="renderAutoSpace"
              label="自动空格"
              v-model="settingsState.renderAutoSpace"
            />
            <FormKit
              type="switch"
              name="renderGfmAutoLink"
              label="自动链接"
              v-model="settingsState.renderGfmAutoLink"
            />
            <FormKit
              type="switch"
              name="renderFootnotes"
              label="脚注"
              v-model="settingsState.renderFootnotes"
            />
            <FormKit
              type="switch"
              name="renderMark"
              label="Mark 标记"
              v-model="settingsState.renderMark"
            />
            <FormKit
              type="switch"
              name="renderFixTermTypo"
              label="术语修正"
              v-model="settingsState.renderFixTermTypo"
            />
            <FormKit
              type="switch"
              name="renderParagraphBeginningSpace"
              label="段首空两格"
              v-model="settingsState.renderParagraphBeginningSpace"
            />
            <FormKit
              type="switch"
              name="renderCodeBlockPreview"
              label="代码块即时渲染"
              v-model="settingsState.renderCodeBlockPreview"
            />
            <FormKit
              type="switch"
              name="renderMathBlockPreview"
              label="公式块即时渲染"
              v-model="settingsState.renderMathBlockPreview"
            />
          </div>
        </div>

        <VSpace>
          <VButton type="secondary" :loading="isImporting" @click="$formkit.submit('my-docs-settings-form')">
            保存设置
          </VButton>
          <VButton @click="router.push({ name: 'DocLibraries' })">取消</VButton>
        </VSpace>
      </FormKit>
    </VCard>
  </div>
</template>

<style scoped>
.doc-settings-section + .doc-settings-section {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #e5e7eb;
}

.doc-settings-title {
  margin: 0 0 16px;
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}

.doc-settings-help {
  margin: -8px 0 16px;
  font-size: 13px;
  color: #6b7280;
}

.doc-settings-grid {
  display: grid;
  gap: 0 16px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.doc-settings-grid--compact {
  align-items: start;
  justify-content: start;
  grid-template-columns: repeat(2, minmax(0, 320px));
}

.doc-settings-grid--compact :deep(.formkit-outer) {
  padding-top: 0;
  padding-bottom: 0;
  margin-bottom: 0;
}

@media (max-width: 720px) {
  .doc-settings-grid--compact {
    grid-template-columns: minmax(0, 1fr);
  }
}

.doc-settings-list-section + .doc-settings-list-section {
  margin-top: 20px;
}

.doc-settings-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.doc-settings-list-head h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}

.doc-settings-list-item {
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f8fafc;
}

.doc-settings-list-item + .doc-settings-list-item {
  margin-top: 12px;
}

.doc-settings-list-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
}

.doc-settings-list-grid--compact {
  grid-template-columns: repeat(auto-fit, minmax(140px, 220px));
}

.doc-settings-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: #374151;
}

.doc-settings-field--wide {
  min-width: 0;
}

.doc-settings-field input,
.doc-settings-field select {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 9px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  color: #111827;
  font-size: 14px;
}

.doc-settings-list-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  font-size: 12px;
  color: #6b7280;
}

.doc-settings-empty {
  margin: 0;
  padding: 14px 16px;
  border: 1px dashed #d1d5db;
  border-radius: 10px;
  color: #6b7280;
  background: #f9fafb;
}
</style>
