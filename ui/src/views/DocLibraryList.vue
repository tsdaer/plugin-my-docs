<script setup lang="ts">
import {
  VButton,
  VCard,
  VPageHeader,
  VEntity,
  VEntityField,
  VEmpty,
  VLoading,
  VPagination,
  VStatusDot,
  VDropdownItem,
  Dialog,
  Toast,
  IconAddCircle,
  IconSettings,
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { axiosInstance } from '@halo-dev/api-client'
import RiBook2Line from '~icons/ri/book-2-line'
import RiImageLine from '~icons/ri/image-line'
import { DocLibraryV1alpha1Api } from '@/api/generated'
import type { DocLibrary } from '@/api/generated'
import DocLibraryEditingModal from '@/components/DocLibraryEditingModal.vue'

const api = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)
const queryClient = useQueryClient()
const router = useRouter()

const page = ref(1)
const size = ref(20)

const editingModalVisible = ref(false)
const selectedLibrary = ref<DocLibrary>()

const { data, isLoading } = useQuery({
  queryKey: ['doc-libraries', page, size],
  queryFn: async () => {
    const { data } = await api.listDocLibrary({
      page: page.value,
      size: size.value,
      sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    return data
  },
})

function handleOpenCreate() {
  selectedLibrary.value = undefined
  editingModalVisible.value = true
}

function handleManageDocs(library: DocLibrary) {
  router.push({ name: 'DocList', params: { libraryName: library.metadata.name } })
}

function handleOpenEdit(library: DocLibrary) {
  selectedLibrary.value = library
  editingModalVisible.value = true
}

function handleCloseModal() {
  editingModalVisible.value = false
  selectedLibrary.value = undefined
}

function handleDelete(library: DocLibrary) {
  Dialog.warning({
    title: '确定要删除该文档库吗？',
    description: `文档库「${library.spec.title}」将被删除，该操作不可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      await api.deleteDocLibrary({ name: library.metadata.name })
      Toast.success('删除成功')
      await queryClient.invalidateQueries({ queryKey: ['doc-libraries'] })
    },
  })
}
</script>

<template>
  <VPageHeader title="文档">
    <template #icon>
      <RiBook2Line class="mr-2 self-center" />
    </template>
    <template #actions>
      <VButton type="secondary" @click="handleOpenCreate">
        <template #icon>
          <IconAddCircle />
        </template>
        新建文档库
      </VButton>
      <VButton @click="router.push({ name: 'DocSettings' })">
        <template #icon>
          <IconSettings />
        </template>
        设置
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard :body-class="['!p-0']">
      <VLoading v-if="isLoading" />

      <VEmpty
        v-else-if="!data?.items?.length"
        message="当前没有文档库，你可以新建一个开始组织文档。"
        title="暂无文档库"
      >
        <template #actions>
          <VButton @click="handleOpenCreate">新建文档库</VButton>
        </template>
      </VEmpty>

      <ul
        v-else
        class="box-border h-full w-full divide-y divide-gray-100"
        role="list"
      >
        <li v-for="library in data.items" :key="library.metadata.name">
          <VEntity>
            <template #start>
              <button
                class="doc-library-row"
                type="button"
                @click="handleManageDocs(library)"
              >
                <div class="doc-library-cover">
                  <img
                    v-if="library.spec.cover"
                    :src="library.spec.cover"
                    :alt="library.spec.title"
                    class="doc-library-cover-image"
                  >
                  <div v-else class="doc-library-cover-placeholder">
                    <RiImageLine class="text-lg" />
                  </div>
                </div>
                <div class="doc-library-main">
                  <p class="doc-library-title">{{ library.spec.title }}</p>
                  <p class="doc-library-slug">{{ library.spec.slug }}</p>
                </div>
              </button>
            </template>
            <template #end>
              <VEntityField>
                <template #description>
                  <span class="doc-library-description">
                    {{ library.spec.description || '暂无描述' }}
                  </span>
                </template>
              </VEntityField>
              <VEntityField>
                <template #description>
                  <span class="doc-library-badge">
                    <VStatusDot v-tooltip="`排序：${library.spec.priority ?? 0}`" state="default" />
                    排序 {{ library.spec.priority ?? 0 }}
                  </span>
                </template>
              </VEntityField>
              <VEntityField v-if="library.metadata.creationTimestamp">
                <template #description>
                  <span class="text-xs text-gray-500">
                    {{ utils.date.format(library.metadata.creationTimestamp) }}
                  </span>
                </template>
              </VEntityField>
            </template>
            <template #dropdownItems>
              <VDropdownItem @click="handleManageDocs(library)">管理文档</VDropdownItem>
              <VDropdownItem @click="handleOpenEdit(library)">编辑</VDropdownItem>
              <VDropdownItem type="danger" @click="handleDelete(library)">
                删除
              </VDropdownItem>
            </template>
          </VEntity>
        </li>
      </ul>

      <template #footer>
        <VPagination
          v-if="data"
          v-model:page="page"
          v-model:size="size"
          :total="data.total"
          :size-options="[20, 30, 50, 100]"
        />
      </template>
    </VCard>
  </div>

  <DocLibraryEditingModal
    v-if="editingModalVisible"
    :library="selectedLibrary"
    @close="handleCloseModal"
  />
</template>

<style scoped>
.doc-library-row {
  display: flex;
  width: 100%;
  min-height: 84px;
  align-items: center;
  gap: 14px;
  padding: 8px 4px;
  border-radius: 12px;
  text-align: left;
  transition: background-color 0.2s ease;
}

.doc-library-row:hover {
  background: #f8fafc;
}

.doc-library-cover {
  display: flex;
  height: 64px;
  width: 96px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(14, 116, 144, 0.12), rgba(15, 118, 110, 0.04)),
    #f8fafc;
}

.doc-library-cover-image {
  height: 100%;
  width: 100%;
  object-fit: cover;
}

.doc-library-cover-placeholder {
  display: flex;
  height: 100%;
  width: 100%;
  align-items: center;
  justify-content: center;
  color: #64748b;
}

.doc-library-main {
  min-width: 0;
  flex: 1;
}

.doc-library-title {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #111827;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.doc-library-slug {
  margin: 6px 0 0;
  overflow: hidden;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-library-description {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-library-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #6b7280;
  font-size: 12px;
  white-space: nowrap;
}
</style>
