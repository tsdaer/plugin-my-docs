<script setup lang="ts">
import {
  VButton,
  VCard,
  VPageHeader,
  VEntity,
  VEntityField,
  VEmpty,
  VLoading,
  VSpace,
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
                class="flex w-full items-center gap-3 rounded-md px-1 py-1 text-left transition-colors hover:bg-gray-50"
                type="button"
                @click="handleManageDocs(library)"
              >
                <div
                  v-if="library.spec.cover"
                  class="h-12 w-16 overflow-hidden rounded-md border border-gray-200 bg-gray-50"
                >
                  <img
                    :src="library.spec.cover"
                    :alt="library.spec.title"
                    class="h-full w-full object-cover"
                  >
                </div>
                <VEntityField
                  :title="library.spec.title"
                  :description="library.spec.slug"
                />
              </button>
            </template>
            <template #end>
              <VEntityField v-if="library.spec.description">
                <template #description>
                  <span class="text-xs text-gray-500 line-clamp-1">
                    {{ library.spec.description }}
                  </span>
                </template>
              </VEntityField>
              <VEntityField>
                <template #description>
                  <VStatusDot v-tooltip="`排序：${library.spec.priority ?? 0}`" state="default" />
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
