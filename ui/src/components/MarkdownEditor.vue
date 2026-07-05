<script setup lang="ts">
import { type AttachmentLike, utils } from '@halo-dev/ui-shared'
import Vditor from 'vditor'
import 'vditor/dist/index.css'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { buildMarkdownAttachment } from '@/utils/markdown-attachment'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    height?: number | string
  }>(),
  {
    modelValue: '',
    height: 'auto',
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
const ATTACHMENT_TOOLBAR_ICON =
  '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M7.5 12.75l6.44-6.44a4.5 4.5 0 1 1 6.36 6.36l-8.56 8.57a6 6 0 0 1-8.49-8.49l8.2-8.2 1.06 1.06-8.2 8.2a4.5 4.5 0 0 0 6.37 6.37l8.56-8.57a3 3 0 0 0-4.24-4.24l-6.44 6.44a1.5 1.5 0 1 0 2.12 2.12l5.74-5.74 1.06 1.06-5.74 5.74a3 3 0 0 1-4.24-4.24Z"/></svg>'

function buildToolbar(): Array<string | IMenuItem> {
  return [
    'emoji',
    'headings',
    'bold',
    'italic',
    'strike',
    'link',
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
      toolbar: ['both', 'code-theme', 'content-theme', 'export', 'outline', 'preview', 'devtools', 'info', 'help'],
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

function fallbackAttachmentLabel(url: string): string {
  const normalized = url.split('?')[0]
  return decodeURIComponent(normalized.split('/').pop() || '附件')
}

function toMarkdown(attachment: AttachmentLike): string | undefined {
  const simple = utils.attachment.convertToSimple(attachment)
  if (!simple?.url) {
    return undefined
  }

  const label = simple.alt?.trim() || fallbackAttachmentLabel(simple.url)
  if (simple.mediaType?.startsWith('image/')) {
    return buildMarkdownAttachment(label, simple.url, 'image')
  }

  return buildMarkdownAttachment(label, simple.url, 'link')
}

function handleAttachmentSelect(attachments: AttachmentLike[]) {
  const markdown = attachments.map(toMarkdown).filter(Boolean).join('\n')
  if (!markdown) {
    closeAttachmentSelector()
    return
  }

  if (ready.value && vditor) {
    vditor.insertValue(markdown, true)
    emit('update:modelValue', vditor.getValue())
  } else {
    const next = [props.modelValue, markdown].filter(Boolean).join('\n')
    emit('update:modelValue', next)
  }

  closeAttachmentSelector()
}
</script>

<template>
  <div ref="el" />
  <AttachmentSelectorModal
    v-if="attachmentSelectorVisible"
    :accepts="['*/*']"
    :max="20"
    @close="closeAttachmentSelector"
    @select="handleAttachmentSelect"
  />
</template>
