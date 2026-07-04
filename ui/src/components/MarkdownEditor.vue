<script setup lang="ts">
import Vditor from 'vditor'
import 'vditor/dist/index.css'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

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

onMounted(() => {
  if (!el.value) {
    return
  }
  vditor = new Vditor(el.value, {
    height: props.height,
    mode: 'ir',
    cache: { enable: false },
    placeholder: '开始撰写文档正文（Markdown）…',
    toolbarConfig: { pin: true },
    after: () => {
      vditor?.setValue(props.modelValue ?? '')
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
</script>

<template>
  <div ref="el" />
</template>
