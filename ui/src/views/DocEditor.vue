<script setup lang="ts">
import { VButton, VPageHeader, VLoading, Toast } from '@halo-dev/components'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { axiosInstance } from '@halo-dev/api-client'
import RiArrowLeftLine from '~icons/ri/arrow-left-line'
import { DocV1alpha1Api } from '@/api/generated'
import type { Doc } from '@/api/generated'
import MarkdownEditor from '@/components/MarkdownEditor.vue'

const route = useRoute()
const router = useRouter()

const docApi = new DocV1alpha1Api(undefined, '', axiosInstance)

const DOC_ENDPOINT = '/apis/console.api.docs.halo.run/v1alpha1/docs'

const libraryName = computed(() => route.params.libraryName as string)
const docName = computed(() => (route.query.name as string) || '')
const parentName = computed(() => (route.query.parent as string) || '')
const isUpdate = computed(() => !!docName.value)

const loading = ref(false)
const saving = ref(false)

// 编辑既有文档时，保留原始 Doc 以在保存时合并 metadata / 未编辑字段。
const original = ref<Doc>()

interface FormState {
  title: string
  slug: string
  parent: string
  priority: number
  published: boolean
}

const formState = ref<FormState>({
  title: '',
  slug: '',
  // 新建子文档时，父节点由路由 query.parent 预置（编辑模式会在 onMounted 覆盖）。
  parent: parentName.value,
  priority: 0,
  published: false,
})

const raw = ref('')

onMounted(async () => {
  if (!isUpdate.value) {
    return
  }
  loading.value = true
  try {
    const { data } = await docApi.getDoc({ name: docName.value })
    original.value = data
    formState.value = {
      title: data.spec.title,
      slug: data.spec.slug,
      parent: data.spec.parent ?? '',
      priority: data.spec.priority ?? 0,
      published: data.spec.published ?? false,
    }
    raw.value = data.spec.raw ?? ''
  } finally {
    loading.value = false
  }
})

function handleBack() {
  router.push({ name: 'DocList', params: { libraryName: libraryName.value } })
}

async function handleSave(values: FormState) {
  saving.value = true
  try {
    if (isUpdate.value && original.value) {
      const toUpdate: Doc = {
        ...original.value,
        spec: {
          ...original.value.spec,
          title: values.title,
          slug: values.slug,
          parent: values.parent || undefined,
          priority: values.priority,
          published: values.published,
          raw: raw.value,
          rawType: 'markdown',
        },
      }
      await axiosInstance.put(`${DOC_ENDPOINT}/${original.value.metadata.name}`, toUpdate)
    } else {
      const toCreate: Doc = {
        apiVersion: 'docs.halo.run/v1alpha1',
        kind: 'Doc',
        metadata: {
          generateName: 'doc-',
          name: '',
        },
        spec: {
          title: values.title,
          slug: values.slug,
          libraryName: libraryName.value,
          parent: values.parent || undefined,
          priority: values.priority,
          published: values.published,
          raw: raw.value,
          rawType: 'markdown',
        },
      }
      await axiosInstance.post(DOC_ENDPOINT, toCreate)
    }
    Toast.success('保存成功')
    handleBack()
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <VPageHeader :title="isUpdate ? '编辑文档' : '新建文档'">
    <template #actions>
      <VButton size="sm" @click="handleBack">
        <template #icon>
          <RiArrowLeftLine />
        </template>
        返回
      </VButton>
      <VButton
        type="secondary"
        :loading="saving"
        @click="$formkit.submit('doc-form')"
      >
        保存
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VLoading v-if="loading" />

    <template v-else>
      <FormKit
        id="doc-form"
        type="form"
        name="doc-form"
        :actions="false"
        @submit="handleSave"
      >
        <FormKit
          type="text"
          name="title"
          label="标题"
          :value="formState.title"
          validation="required|length:1,200"
        />
        <FormKit
          type="text"
          name="slug"
          label="别名"
          help="访问别名，库内唯一，仅限小写字母、数字与连字符"
          :value="formState.slug"
          :validation="[['required'], ['matches', /^[a-z0-9-]+$/], ['length', 1, 200]]"
        />
        <FormKit
          type="number"
          name="priority"
          label="排序"
          help="同级排序权重，值越小越靠前"
          :value="formState.priority"
          validation="number"
        />
        <FormKit
          type="switch"
          name="published"
          label="发布"
          :value="formState.published"
        />
      </FormKit>

      <div class="mt-4">
        <MarkdownEditor v-model="raw" height="60vh" />
      </div>
    </template>
  </div>
</template>
