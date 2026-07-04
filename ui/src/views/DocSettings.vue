<script setup lang="ts">
import { VButton, VCard, VLoading, VPageHeader, VSpace, Toast, IconSettings } from '@halo-dev/components'
import { coreApiClient, axiosInstance } from '@halo-dev/api-client'
import type { ConfigMap } from '@halo-dev/api-client'
import { computed } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import { DocLibraryV1alpha1Api } from '@/api/generated'

const CONFIG_MAP_NAME = 'my-docs-configmap'
const CONFIG_GROUP = 'basic'

interface SettingsForm {
  defaultSort: 'priorityAsc' | 'createdDesc' | 'titleAsc'
  pageSize: number
  defaultLibraryName: string
}

const defaultSettings: SettingsForm = {
  defaultSort: 'priorityAsc',
  pageSize: 20,
  defaultLibraryName: '',
}

const router = useRouter()
const queryClient = useQueryClient()
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)

const { data: configMap, isLoading: isConfigLoading } = useQuery({
  queryKey: ['my-docs-settings-configmap'],
  queryFn: async () => {
    const { data } = await coreApiClient.configMap.listConfigMap({
      page: 1,
      size: 1,
      fieldSelector: [`metadata.name=${CONFIG_MAP_NAME}`],
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

const formState = computed<SettingsForm>(() => {
  const raw = configMap.value?.data?.[CONFIG_GROUP]
  if (!raw) {
    return defaultSettings
  }

  try {
    const parsed = JSON.parse(raw) as Partial<SettingsForm>
    return {
      defaultSort: parsed.defaultSort ?? defaultSettings.defaultSort,
      pageSize: Number(parsed.pageSize) || defaultSettings.pageSize,
      defaultLibraryName: parsed.defaultLibraryName ?? defaultSettings.defaultLibraryName,
    }
  } catch {
    return defaultSettings
  }
})

const libraryOptions = computed(() => [
  { label: '不指定', value: '' },
  ...(libraries.value ?? []).map((library) => ({
    label: library.spec.title,
    value: library.metadata.name,
  })),
])

async function handleSubmit(values: SettingsForm) {
  const normalized: SettingsForm = {
    defaultSort: values.defaultSort,
    pageSize: Number(values.pageSize) || defaultSettings.pageSize,
    defaultLibraryName: values.defaultLibraryName ?? '',
  }

  const next: ConfigMap = configMap.value
    ? {
        ...configMap.value,
        data: {
          ...(configMap.value.data ?? {}),
          [CONFIG_GROUP]: JSON.stringify(normalized),
        },
      }
    : {
        apiVersion: 'v1alpha1',
        kind: 'ConfigMap',
        metadata: {
          name: CONFIG_MAP_NAME,
        },
        data: {
          [CONFIG_GROUP]: JSON.stringify(normalized),
        },
      }

  if (configMap.value) {
    await coreApiClient.configMap.updateConfigMap({
      name: CONFIG_MAP_NAME,
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
        <FormKit
          type="select"
          name="defaultSort"
          label="默认排序"
          :value="formState.defaultSort"
          :options="[
            { label: '排序权重升序', value: 'priorityAsc' },
            { label: '创建时间倒序', value: 'createdDesc' },
            { label: '标题升序', value: 'titleAsc' },
          ]"
          validation="required"
        />
        <FormKit
          type="number"
          name="pageSize"
          label="每页数量"
          :value="formState.pageSize"
          validation="required|min:5|max:100"
        />
        <FormKit
          type="select"
          name="defaultLibraryName"
          label="默认文档库"
          :value="formState.defaultLibraryName"
          :options="libraryOptions"
          searchable
          clearable
        />

        <VSpace>
          <VButton type="secondary" @click="$formkit.submit('my-docs-settings-form')">
            保存设置
          </VButton>
          <VButton @click="router.push({ name: 'DocLibraries' })">取消</VButton>
        </VSpace>
      </FormKit>
    </VCard>
  </div>
</template>
