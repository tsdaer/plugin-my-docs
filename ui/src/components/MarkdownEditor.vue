<script setup lang="ts">
import { type AttachmentLike, utils } from '@halo-dev/ui-shared'
import { Toast, VButton, VModal, VSpace } from '@halo-dev/components'
import Vditor from 'vditor'
import 'vditor/dist/index.css'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { buildMarkdownAttachment, type MarkdownImageOptions } from '@/utils/markdown-attachment'
import { buildMarkdownDocLink } from '@/utils/doc-link'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    height?: number | string
    docLinks?: Array<{
      title: string
      slug: string
    }>
  }>(),
  {
    modelValue: '',
    height: 'auto',
    docLinks: () => [],
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const el = ref<HTMLElement>()
let vditor: Vditor | undefined
// 标记初始化完成前不回传，避免 after 回填初值时触发一次多余的 update。
const ready = ref(false)
const attachmentSelectorVisible = ref(false)
const imageOptionsModalVisible = ref(false)
const docLinkModalVisible = ref(false)
const pendingAttachments = ref<AttachmentLike[]>([])
const docLinkForm = ref({
  slug: '',
  label: '',
  anchor: '',
})
const imageOptionForm = ref<{
  width?: number
  align: '' | 'left' | 'center' | 'right'
  pad?: number
}>({
  width: undefined,
  align: '',
  pad: undefined,
})
const ATTACHMENT_TOOLBAR_ICON =
  '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M7.5 12.75l6.44-6.44a4.5 4.5 0 1 1 6.36 6.36l-8.56 8.57a6 6 0 0 1-8.49-8.49l8.2-8.2 1.06 1.06-8.2 8.2a4.5 4.5 0 0 0 6.37 6.37l8.56-8.57a3 3 0 0 0-4.24-4.24l-6.44 6.44a1.5 1.5 0 1 0 2.12 2.12l5.74-5.74 1.06 1.06-5.74 5.74a3 3 0 0 1-4.24-4.24Z"/></svg>'
const DOC_LINK_TOOLBAR_ICON =
  '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M8 5a3 3 0 0 0 0 6h3v2H8A5 5 0 0 1 8 3h3v2H8Zm5 0h3a5 5 0 1 1 0 10h-3v-2h3a3 3 0 1 0 0-6h-3V5Zm-4 6h6v2H9v-2Zm-2 7h10v2H7v-2Z"/></svg>'
const imageAlignOptions = [
  { label: '不设置', value: '' },
  { label: '左对齐', value: 'left' },
  { label: '居中', value: 'center' },
  { label: '右对齐', value: 'right' },
] as const

const docLinkOptions = computed(() =>
  props.docLinks
    .filter((item) => item.slug && item.title)
    .map((item) => ({
      label: `${item.title} (${item.slug})`,
      value: item.slug,
      title: item.title,
    })),
)
const pendingImageCount = computed(
  () => pendingAttachments.value.filter((item) => isImageAttachment(item)).length,
)
const pendingFileCount = computed(() => pendingAttachments.value.length - pendingImageCount.value)

const editorHeight = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : props.height,
)

function insertMarkdown(markdown: string) {
  if (ready.value && vditor) {
    vditor.insertValue(markdown, true)
    emit('update:modelValue', vditor.getValue())
    return
  }

  const next = [props.modelValue, markdown].filter(Boolean).join('\n')
  emit('update:modelValue', next)
}

function buildToolbar(): Array<string | IMenuItem> {
  return [
    'emoji',
    'headings',
    'bold',
    'italic',
    'strike',
    'link',
    {
      name: 'doc-link',
      tip: '文档链接',
      icon: DOC_LINK_TOOLBAR_ICON,
      click: () => {
        handleOpenDocLinkModal()
      },
    },
    '|',
    'list',
    'ordered-list',
    'check',
    'outdent',
    'indent',
    '|',
    'quote',
    'line',
    'code',
    'inline-code',
    'insert-before',
    'insert-after',
    '|',
    {
      name: 'upload',
      tip: '附件库',
      icon: ATTACHMENT_TOOLBAR_ICON,
      click: () => {
        attachmentSelectorVisible.value = true
      },
    },
    'record',
    'table',
    '|',
    'undo',
    'redo',
    '|',
    'fullscreen',
    'edit-mode',
    {
      name: 'more',
      toolbar: [
        'both',
        'code-theme',
        'content-theme',
        'export',
        'outline',
        'preview',
        'devtools',
        'info',
        'help',
      ],
    },
  ]
}

function hijackUploadToolbar() {
  const uploadItem = vditor?.vditor?.toolbar?.elements?.upload
  const button = uploadItem?.firstElementChild as HTMLElement | null
  const input = uploadItem?.querySelector('input') as HTMLInputElement | null

  if (!button) {
    return
  }

  if (input) {
    input.disabled = true
    input.tabIndex = -1
    input.style.display = 'none'
  }

  if (button.dataset.mydocsAttachmentBound === 'true') {
    return
  }

  button.dataset.mydocsAttachmentBound = 'true'
  button.addEventListener('click', (event) => {
    event.preventDefault()
    event.stopPropagation()
    attachmentSelectorVisible.value = true
  })
}

onMounted(() => {
  if (!el.value) {
    return
  }
  vditor = new Vditor(el.value, {
    height: props.height,
    mode: 'ir',
    cache: { enable: false },
    placeholder: '开始撰写文档正文（Markdown）…',
    toolbar: buildToolbar(),
    toolbarConfig: { pin: true },
    after: () => {
      vditor?.setValue(props.modelValue ?? '')
      hijackUploadToolbar()
      ready.value = true
    },
    input: (value: string) => {
      emit('update:modelValue', value)
    },
  })
})

// 外部值变化（如编辑模式异步回填）时同步进编辑器，避免与用户输入回环。
watch(
  () => props.modelValue,
  (value) => {
    if (ready.value && vditor && value !== vditor.getValue()) {
      vditor.setValue(value ?? '')
    }
  },
)

onBeforeUnmount(() => {
  // 仅在 Vditor 完成异步初始化（after 回调已触发）后才销毁：
  // 若在初始化前卸载（如快速保存/返回），其内部状态未建立，destroy() 会
  // 访问 this.vditor.element 抛错并打断路由导航。try/catch 再兜底一层。
  if (ready.value && vditor) {
    try {
      vditor.destroy()
    } catch (e) {
      console.warn('[my-docs] Vditor destroy failed:', e)
    }
  }
  vditor = undefined
  ready.value = false
})

function closeAttachmentSelector() {
  attachmentSelectorVisible.value = false
}

function resetImageOptionForm() {
  imageOptionForm.value = {
    width: undefined,
    align: '',
    pad: undefined,
  }
}

function closeImageOptionsModal() {
  imageOptionsModalVisible.value = false
  pendingAttachments.value = []
  resetImageOptionForm()
}

function handleOpenDocLinkModal() {
  if (!docLinkOptions.value.length) {
    Toast.warning('当前文档库暂无可链接文档')
    return
  }

  const [firstDoc] = docLinkOptions.value
  docLinkForm.value = {
    slug: firstDoc?.value ?? '',
    label: firstDoc?.title ?? '',
    anchor: '',
  }
  docLinkModalVisible.value = true
}

function handleDocSlugChange(slug: string) {
  docLinkForm.value.slug = slug
  const target = docLinkOptions.value.find((item) => item.value === slug)
  if (target) {
    docLinkForm.value.label = target.title
  }
}

function closeDocLinkModal() {
  docLinkModalVisible.value = false
}

function handleInsertDocLink() {
  const slug = docLinkForm.value.slug.trim()
  if (!slug) {
    Toast.warning('请先选择要链接的文档')
    return
  }

  insertMarkdown(
    buildMarkdownDocLink(docLinkForm.value.label.trim(), slug, docLinkForm.value.anchor.trim()),
  )
  closeDocLinkModal()
}

function fallbackAttachmentLabel(url: string): string {
  const normalized = url.split('?')[0]
  return decodeURIComponent(normalized.split('/').pop() || '附件')
}

function toOptionalNumber(value: unknown): number | undefined {
  if (value === '' || value === null || value === undefined) {
    return undefined
  }

  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function isImageAttachment(attachment: AttachmentLike): boolean {
  const simple = utils.attachment.convertToSimple(attachment)
  return !!simple?.url && !!simple.mediaType?.startsWith('image/')
}

function toMarkdown(
  attachment: AttachmentLike,
  imageOptions?: MarkdownImageOptions,
): string | undefined {
  const simple = utils.attachment.convertToSimple(attachment)
  if (!simple?.url) {
    return undefined
  }

  const label = simple.alt?.trim() || fallbackAttachmentLabel(simple.url)
  if (isImageAttachment(attachment)) {
    return buildMarkdownAttachment(label, simple.url, 'image', imageOptions)
  }

  return buildMarkdownAttachment(label, simple.url, 'link')
}

function buildAttachmentMarkdown(
  attachments: AttachmentLike[],
  imageOptions?: MarkdownImageOptions,
): string {
  return attachments
    .map((item) => toMarkdown(item, imageOptions))
    .filter(Boolean)
    .join('\n')
}

function resolveImageOptions(): MarkdownImageOptions | null | undefined {
  const width = toOptionalNumber(imageOptionForm.value.width)
  if (
    imageOptionForm.value.width !== undefined &&
    (width === undefined || width < 1 || width > 100)
  ) {
    Toast.warning('图片宽度百分比需在 1 到 100 之间')
    return null
  }

  const pad = toOptionalNumber(imageOptionForm.value.pad)
  if (imageOptionForm.value.pad !== undefined && (pad === undefined || pad < 0)) {
    Toast.warning('图片四周填充需为大于等于 0 的像素值')
    return null
  }

  const options: MarkdownImageOptions = {}
  if (width !== undefined) {
    options.width = width
  }
  if (imageOptionForm.value.align) {
    options.align = imageOptionForm.value.align
  }
  if (pad !== undefined) {
    options.pad = pad
  }

  return Object.keys(options).length ? options : undefined
}

function insertPendingAttachments(imageOptions?: MarkdownImageOptions) {
  const markdown = buildAttachmentMarkdown(pendingAttachments.value, imageOptions)
  if (!markdown) {
    closeImageOptionsModal()
    return
  }

  insertMarkdown(markdown)
  closeImageOptionsModal()
}

function handleInsertPendingAttachments() {
  const imageOptions = resolveImageOptions()
  if (imageOptions === null) {
    return
  }

  insertPendingAttachments(imageOptions)
}

function handleAttachmentSelect(attachments: AttachmentLike[]) {
  if (!attachments.length) {
    closeAttachmentSelector()
    return
  }

  closeAttachmentSelector()
  if (attachments.some((item) => isImageAttachment(item))) {
    pendingAttachments.value = attachments
    resetImageOptionForm()
    imageOptionsModalVisible.value = true
    return
  }

  const markdown = buildAttachmentMarkdown(attachments)
  if (!markdown) {
    return
  }

  insertMarkdown(markdown)
}
</script>

<template>
  <div
    ref="el"
    class="markdown-editor-root"
    :style="editorHeight === 'auto' ? undefined : { height: editorHeight }"
  />
  <AttachmentSelectorModal
    v-if="attachmentSelectorVisible"
    :accepts="['*/*']"
    :max="20"
    @close="closeAttachmentSelector"
    @select="handleAttachmentSelect"
  />
  <VModal
    v-if="imageOptionsModalVisible"
    title="插入图片参数"
    :width="560"
    @close="closeImageOptionsModal"
  >
    <div class="image-option-summary">
      本次已选择 {{ pendingImageCount }} 张图片
      <span v-if="pendingFileCount > 0"
        >，另有 {{ pendingFileCount }} 个文件链接将保持普通插入</span
      >
      。
    </div>
    <FormKit
      type="number"
      label="宽度百分比"
      :value="imageOptionForm.width"
      help="可选，1 到 100。将写入 #md-width。"
      validation="number"
      @input="imageOptionForm.width = toOptionalNumber($event)"
    />
    <FormKit
      type="select"
      label="对齐方式"
      :value="imageOptionForm.align"
      :options="imageAlignOptions"
      help="可选，将写入 #md-align，支持 left / center / right。"
      @input="imageOptionForm.align = $event"
    />
    <FormKit
      type="number"
      label="四周填充（px）"
      :value="imageOptionForm.pad"
      help="可选，填写非负整数，将写入 #md-pad。"
      validation="number"
      @input="imageOptionForm.pad = toOptionalNumber($event)"
    />

    <template #footer>
      <VSpace>
        <VButton type="secondary" @click="handleInsertPendingAttachments">插入</VButton>
        <VButton @click="insertPendingAttachments()">不加参数</VButton>
        <VButton @click="closeImageOptionsModal">取消</VButton>
      </VSpace>
    </template>
  </VModal>
  <VModal v-if="docLinkModalVisible" title="插入文档链接" :width="560" @close="closeDocLinkModal">
    <FormKit
      id="doc-link-form"
      type="form"
      name="doc-link-form"
      :actions="false"
      @submit="handleInsertDocLink"
    >
      <FormKit
        type="select"
        name="slug"
        label="目标文档"
        :value="docLinkForm.slug"
        :options="docLinkOptions"
        validation="required"
        @input="handleDocSlugChange"
      />
      <FormKit
        type="text"
        name="label"
        label="链接文字"
        :value="docLinkForm.label"
        validation="required|length:1,200"
        @input="docLinkForm.label = $event"
      />
      <FormKit
        type="text"
        name="anchor"
        label="标题锚点"
        help="可选，填写标题 id，不需要包含 #。"
        :value="docLinkForm.anchor"
        validation="length:0,200"
        @input="docLinkForm.anchor = $event"
      />
    </FormKit>

    <template #footer>
      <VSpace>
        <VButton type="secondary" @click="$formkit.submit('doc-link-form')">插入</VButton>
        <VButton @click="closeDocLinkModal">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.markdown-editor-root {
  min-height: 320px;
}

.markdown-editor-root :deep(.vditor) {
  height: 100% !important;
  display: flex;
  flex-direction: column;
}

.markdown-editor-root :deep(.vditor-content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.markdown-editor-root :deep(.vditor-ir),
.markdown-editor-root :deep(.vditor-wysiwyg),
.markdown-editor-root :deep(.vditor-sv) {
  height: 100%;
  overflow: auto;
}

.image-option-summary {
  margin-bottom: 12px;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.6;
}
</style>
