<script setup lang="ts">
import { VModal, VButton, VSpace, Toast } from '@halo-dev/components'
import { computed, ref, watch } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { axiosInstance } from '@halo-dev/api-client'
import { DocLibraryV1alpha1Api } from '@/api/generated'
import type { DocLibrary } from '@/api/generated'

const props = withDefaults(
  defineProps<{
    library?: DocLibrary
  }>(),
  {
    library: undefined,
  },
)

const emit = defineEmits<{
  (e: 'close'): void
}>()

const api = new DocLibraryV1alpha1Api(undefined, '', axiosInstance)
const queryClient = useQueryClient()

const isUpdate = computed(() => !!props.library?.metadata?.name)
const modalTitle = computed(() => (isUpdate.value ? '编辑文档库' : '新建文档库'))

const submitting = ref(false)

interface FormState {
  title: string
  slug: string
  description: string
  cover: string
  priority: number
}

const formState = ref<FormState>({
  title: '',
  slug: '',
  description: '',
  cover: '',
  priority: 0,
})

watch(
  () => props.library,
  (value) => {
    if (value) {
      formState.value = {
        title: value.spec.title,
        slug: value.spec.slug,
        description: value.spec.description ?? '',
        cover: value.spec.cover ?? '',
        priority: value.spec.priority ?? 0,
      }
    }
  },
  { immediate: true },
)

async function handleSubmit(values: FormState) {
  submitting.value = true
  try {
    if (isUpdate.value && props.library) {
      const toUpdate: DocLibrary = {
        ...props.library,
        spec: {
          ...props.library.spec,
          title: values.title,
          slug: values.slug,
          description: values.description,
          cover: values.cover,
          priority: values.priority,
        },
      }
      await api.updateDocLibrary({
        name: props.library.metadata.name,
        docLibrary: toUpdate,
      })
    } else {
      const toCreate: DocLibrary = {
        apiVersion: 'docs.halo.run/v1alpha1',
        kind: 'DocLibrary',
        metadata: {
          generateName: 'doc-library-',
          name: '',
        },
        spec: {
          title: values.title,
          slug: values.slug,
          description: values.description,
          cover: values.cover,
          priority: values.priority,
        },
      }
      await api.createDocLibrary({ docLibrary: toCreate })
    }
    Toast.success(isUpdate.value ? '更新成功' : '创建成功')
    await queryClient.invalidateQueries({ queryKey: ['doc-libraries'] })
    emit('close')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <VModal :title="modalTitle" :width="600" @close="emit('close')">
    <FormKit
      id="doc-library-form"
      type="form"
      name="doc-library-form"
      :actions="false"
      @submit="handleSubmit"
    >
      <FormKit
        type="text"
        name="title"
        label="标题"
        :value="formState.title"
        validation="required|length:1,100"
      />
      <FormKit
        type="text"
        name="slug"
        label="别名"
        help="访问别名，全局唯一，仅限小写字母、数字与连字符"
        :value="formState.slug"
        :validation="[['required'], ['matches', /^[a-z0-9-]+$/], ['length', 1, 100]]"
      />
      <FormKit
        type="textarea"
        name="description"
        label="描述"
        :value="formState.description"
        validation="length:0,500"
        :auto-height="true"
      />
      <FormKit
        type="text"
        name="cover"
        label="封面图"
        help="填写可公开访问的图片地址，用于列表卡片和前台页面展示。"
        :value="formState.cover"
        validation="length:0,1000"
      />
      <FormKit
        type="number"
        name="priority"
        label="排序"
        help="值越小越靠前"
        :value="formState.priority"
        validation="number"
      />
    </FormKit>

    <template #footer>
      <VSpace>
        <VButton
          type="secondary"
          :loading="submitting"
          @click="$formkit.submit('doc-library-form')"
        >
          提交
        </VButton>
        <VButton @click="emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
