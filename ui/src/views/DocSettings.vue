<script setup lang="ts">
import { VButton, VCard, VLoading, VPageHeader, VSpace, Toast, IconSettings } from '@halo-dev/components'
import { coreApiClient, axiosInstance } from '@halo-dev/api-client'
import type { ConfigMap } from '@halo-dev/api-client'
import { computed } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import { DocLibraryV1alpha1Api } from '@/api/generated'
import {
  MY_DOCS_CONFIG_GROUP,
  MY_DOCS_CONFIG_MAP_NAME,
  defaultMyDocsSettings,
  parseMyDocsSettings,
  stringifyMyDocsSettings,
  type MyDocsSettings,
} from '@/utils/my-docs-settings'

const router = useRouter()
const queryClient = useQueryClient()
const libraryApi = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)

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

const formState = computed<MyDocsSettings>(() =>
  parseMyDocsSettings(configMap.value?.data?.[MY_DOCS_CONFIG_GROUP]),
)

const libraryOptions = computed(() => [
  { label: '不指定', value: '' },
  ...(libraries.value ?? []).map((library) => ({
    label: library.spec.title,
    value: library.metadata.name,
  })),
])

async function handleSubmit(values: MyDocsSettings) {
  const normalized: MyDocsSettings = {
    defaultSort: values.defaultSort,
    pageSize: Number(values.pageSize) || defaultMyDocsSettings.pageSize,
    defaultLibraryName: values.defaultLibraryName ?? '',
    renderContentTheme: values.renderContentTheme,
    renderCodeTheme: values.renderCodeTheme?.trim() || defaultMyDocsSettings.renderCodeTheme,
    renderLineNumber: !!values.renderLineNumber,
    renderAutoSpace: !!values.renderAutoSpace,
    renderGfmAutoLink: !!values.renderGfmAutoLink,
    renderFootnotes: !!values.renderFootnotes,
    renderMark: !!values.renderMark,
    renderFixTermTypo: !!values.renderFixTermTypo,
    renderParagraphBeginningSpace: !!values.renderParagraphBeginningSpace,
    renderCodeBlockPreview: !!values.renderCodeBlockPreview,
    renderMathBlockPreview: !!values.renderMathBlockPreview,
  }

  const next: ConfigMap = configMap.value
    ? {
        ...configMap.value,
        data: {
          ...(configMap.value.data ?? {}),
          [MY_DOCS_CONFIG_GROUP]: stringifyMyDocsSettings(normalized),
        },
      }
    : {
        apiVersion: 'v1alpha1',
        kind: 'ConfigMap',
        metadata: {
          name: MY_DOCS_CONFIG_MAP_NAME,
        },
        data: {
          [MY_DOCS_CONFIG_GROUP]: stringifyMyDocsSettings(normalized),
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
          <h3 class="doc-settings-title">基础设置</h3>
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
              :value="formState.renderContentTheme"
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
              :value="formState.renderCodeTheme"
              help="填写 Vditor / Chroma 代码主题名，例如 github、monokai。"
              validation="required|length:1,100"
            />
          </div>
          <div class="doc-settings-grid">
            <FormKit
              type="switch"
              name="renderLineNumber"
              label="代码行号"
              :value="formState.renderLineNumber"
            />
            <FormKit
              type="switch"
              name="renderAutoSpace"
              label="自动空格"
              :value="formState.renderAutoSpace"
            />
            <FormKit
              type="switch"
              name="renderGfmAutoLink"
              label="自动链接"
              :value="formState.renderGfmAutoLink"
            />
            <FormKit
              type="switch"
              name="renderFootnotes"
              label="脚注"
              :value="formState.renderFootnotes"
            />
            <FormKit
              type="switch"
              name="renderMark"
              label="Mark 标记"
              :value="formState.renderMark"
            />
            <FormKit
              type="switch"
              name="renderFixTermTypo"
              label="术语修正"
              :value="formState.renderFixTermTypo"
            />
            <FormKit
              type="switch"
              name="renderParagraphBeginningSpace"
              label="段首空两格"
              :value="formState.renderParagraphBeginningSpace"
            />
            <FormKit
              type="switch"
              name="renderCodeBlockPreview"
              label="代码块即时渲染"
              :value="formState.renderCodeBlockPreview"
            />
            <FormKit
              type="switch"
              name="renderMathBlockPreview"
              label="公式块即时渲染"
              :value="formState.renderMathBlockPreview"
            />
          </div>
        </div>

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
</style>
