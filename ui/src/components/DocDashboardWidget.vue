<script setup lang="ts">
import { VButton, VLoading, IconBookRead, IconSettings } from '@halo-dev/components'
import { axiosInstance } from '@halo-dev/api-client'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { DocLibraryV1alpha1Api, DocV1alpha1Api } from '@/api/generated'

const router = useRouter()
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)
const docApi = new DocV1alpha1Api(undefined, '', axiosInstance)

const { data, isLoading } = useQuery({
  queryKey: ['my-docs-dashboard-stats'],
  queryFn: async () => {
    const [{ data: libraries }, { data: docs }] = await Promise.all([
      libraryApi.listDocLibrary({ page: 1, size: 1 }),
      docApi.listDoc({ page: 1, size: 1 }),
    ])
    return {
      libraries: libraries.total,
      docs: docs.total,
    }
  },
})
</script>

<template>
  <div class="p-4">
    <div class="mb-4 flex items-center justify-between gap-3">
      <div class="min-w-0">
        <div class="text-sm font-medium text-gray-900">文档</div>
        <div class="mt-1 text-xs text-gray-500">文档库与文档总量</div>
      </div>
      <IconBookRead class="h-5 w-5 shrink-0 text-gray-500" />
    </div>

    <VLoading v-if="isLoading" />

    <div v-else class="grid grid-cols-2 gap-3">
      <div class="rounded border border-gray-100 p-3">
        <div class="text-2xl font-semibold text-gray-900">{{ data?.libraries ?? 0 }}</div>
        <div class="mt-1 text-xs text-gray-500">文档库</div>
      </div>
      <div class="rounded border border-gray-100 p-3">
        <div class="text-2xl font-semibold text-gray-900">{{ data?.docs ?? 0 }}</div>
        <div class="mt-1 text-xs text-gray-500">文档</div>
      </div>
    </div>

    <div class="mt-4 flex flex-wrap gap-2">
      <VButton size="sm" type="secondary" @click="router.push({ name: 'DocLibraries' })">
        <template #icon>
          <IconBookRead />
        </template>
        管理文档
      </VButton>
      <VButton size="sm" @click="router.push({ name: 'DocSettings' })">
        <template #icon>
          <IconSettings />
        </template>
        文档设置
      </VButton>
    </div>
  </div>
</template>
