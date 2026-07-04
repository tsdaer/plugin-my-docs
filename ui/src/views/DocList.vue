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
} from '@halo-dev/components'
import { utils } from '@halo-dev/ui-shared'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { axiosInstance } from '@halo-dev/api-client'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import { DocV1alpha1Api, DocLibraryV1alpha1Api } from '@/api/generated'
import type { Doc } from '@/api/generated'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()

const docApi = new DocV1alpha1Api(undefined, '', axiosInstance)
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)

const libraryName = computed(() => route.params.libraryName as string)

const page = ref(1)
const size = ref(20)

const { data: library } = useQuery({
  queryKey: ['doc-library', libraryName],
  queryFn: async () => {
    const { data } = await libraryApi.getDocLibrary({ name: libraryName.value })
    return data
  },
})

const { data, isLoading } = useQuery({
  queryKey: ['docs', libraryName, page, size],
  queryFn: async () => {
    const { data } = await docApi.listDoc({
      page: page.value,
      size: size.value,
      fieldSelector: [`spec.libraryName=${libraryName.value}`],
      sort: ['spec.priority,asc', 'metadata.creationTimestamp,desc'],
    })
    return data
  },
})

function handleBack() {
  router.push({ name: 'DocLibraries' })
}

function handleCreate() {
  router.push({ name: 'DocEditor', params: { libraryName: libraryName.value } })
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
    description: `文档「${doc.spec.title}」将被删除，该操作不可恢复。`,
    confirmType: 'danger',
    onConfirm: async () => {
      await docApi.deleteDoc({ name: doc.metadata.name })
      Toast.success('删除成功')
      await queryClient.invalidateQueries({ queryKey: ['docs'] })
    },
  })
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
      <VButton type="secondary" @click="handleCreate">
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
        v-else-if="!data?.items?.length"
        message="当前文档库还没有文档，你可以新建一篇开始撰写。"
        title="暂无文档"
      >
        <template #actions>
          <VButton @click="handleCreate">新建文档</VButton>
        </template>
      </VEmpty>

      <ul
        v-else
        class="box-border h-full w-full divide-y divide-gray-100"
        role="list"
      >
        <li v-for="doc in data.items" :key="doc.metadata.name">
          <VEntity>
            <template #start>
              <VEntityField
                :title="doc.spec.title"
                :description="doc.spec.slug"
              />
            </template>
            <template #end>
              <VEntityField>
                <template #description>
                  <VStatusDot
                    v-tooltip="doc.spec.published ? '已发布' : '草稿'"
                    :state="doc.spec.published ? 'success' : 'default'"
                    :text="doc.spec.published ? '已发布' : '草稿'"
                  />
                </template>
              </VEntityField>
              <VEntityField v-if="doc.metadata.creationTimestamp">
                <template #description>
                  <span class="text-xs text-gray-500">
                    {{ utils.date.format(doc.metadata.creationTimestamp) }}
                  </span>
                </template>
              </VEntityField>
            </template>
            <template #dropdownItems>
              <VDropdownItem @click="handleEdit(doc)">编辑</VDropdownItem>
              <VDropdownItem type="danger" @click="handleDelete(doc)">
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
</template>
